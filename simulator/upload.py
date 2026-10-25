#!/usr/bin/env python3
"""Resume a file through the ordinary upload API. Credentials stay in private config only."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid

ROOT = Path(__file__).resolve().parents[1]
LOCAL = (ROOT / ".local").resolve()
TYPES = {"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg",
         "pdf": "application/pdf", "mp4": "video/mp4"}
STATUSES = {"UPLOADING", "VERIFYING", "COMPLETED", "FAILED", "EXPIRED"}
BLOCK = 64 * 1024


class UploadError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise UploadError(message)


def identifier(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def private_path(value):
    path = Path(value)
    path = (ROOT / path).resolve() if not path.is_absolute() else path.resolve()
    require(path.is_relative_to(LOCAL), "Configuration and checkpoint must be under .local/")
    return path


def read_private(path):
    require(path.is_file() and stat.S_IMODE(path.stat().st_mode) == 0o600,
            "Use private files with mode 0600 under .local/")
    try:
        value = json.loads(path.read_text())
    except (OSError, ValueError):
        raise UploadError("Cannot read private upload configuration or checkpoint") from None
    require(isinstance(value, dict), "Invalid private upload document")
    return value


def save_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=".upload-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump(value, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def user_subject(token):
    # This only binds checkpoints across refreshed tokens; the backend authenticates the JWT.
    require(isinstance(token, str) and len(token) <= 16384
            and re.fullmatch(r"[A-Za-z0-9_.-]+", token) is not None,
            "Invalid private user access token")
    pieces = token.split(".")
    require(len(pieces) == 3, "Use a user JWT in private configuration")
    try:
        payload = pieces[1] + "=" * (-len(pieces[1]) % 4)
        claims = json.loads(base64.b64decode(payload, altchars=b"-_", validate=True))
        subject = claims.get("sub")
    except (ValueError, TypeError, AttributeError):
        raise UploadError("Invalid private user access token") from None
    require(identifier(subject), "Invalid private user identity")
    return subject


def load_config(value):
    path = private_path(value)
    config = read_private(path)
    require(type(config.get("formatVersion")) is int and config["formatVersion"] == 1,
            "Unsupported upload configuration")
    origin = config.get("baseUrl")
    require(isinstance(origin, str) and not any(ord(c) < 32 or ord(c) == 127 for c in origin),
            "Invalid backend origin")
    parsed = urlsplit(origin)
    require(parsed.scheme in ("http", "https") and parsed.hostname and not parsed.username
            and not parsed.password and not parsed.query and not parsed.fragment
            and parsed.path in ("", "/"), "Use an HTTP backend origin without credentials")
    try:
        port = parsed.port
    except ValueError:
        raise UploadError("Invalid backend port") from None
    require(port is None or 1 <= port <= 65535, "Invalid backend port")
    for key in ("requirementId", "executionId"):
        require(identifier(config.get(key)), "Invalid requirement or execution identifier")
    auth = config.get("auth")
    require(isinstance(auth, dict), "A private user or device identity is required")
    if auth.get("type") == "USER":
        require(set(auth) == {"type", "accessToken"}, "Invalid private user configuration")
        auth_scope = {"type": "USER", "actorId": user_subject(auth["accessToken"])}
    elif auth.get("type") == "DEVICE":
        require(set(auth) == {"type", "deviceId", "deviceToken"} and identifier(auth.get("deviceId"))
                and isinstance(auth.get("deviceToken"), str)
                and re.fullmatch(r"[0-9a-f]{64}", auth["deviceToken"]) is not None,
                "Invalid private device configuration")
        auth_scope = {"type": "DEVICE", "actorId": auth["deviceId"]}
    else:
        raise UploadError("Use USER or DEVICE authentication")
    file_value = config.get("filePath")
    require(isinstance(file_value, str) and file_value, "A local file is required")
    file = Path(file_value)
    file = (ROOT / file).resolve() if not file.is_absolute() else file.resolve()
    require(file.is_file(), "Cannot read configured upload file")
    extension = file.suffix[1:].lower()
    content_type = config.get("contentType", TYPES.get(extension))
    require(extension in TYPES and content_type == TYPES[extension], "Unsupported file extension or MIME type")
    state_path = private_path(config.get("stateFile", str(path.with_suffix(".state.json"))))
    require(state_path != path and state_path != file, "Checkpoint must be separate from configuration and source")
    return {"baseUrl": origin.rstrip("/"), "requirementId": config["requirementId"],
            "executionId": config["executionId"], "filePath": file, "contentType": content_type,
            "stateFile": state_path, "auth": auth, "authScope": auth_scope}


def file_stamp(path):
    info = path.stat()
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns


def file_hash(path):
    before = file_stamp(path)
    digest, size = hashlib.sha256(), 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(BLOCK), b""):
            size += len(block)
            digest.update(block)
    require(before == file_stamp(path) and size == before[2] and size > 0,
            "The upload file is empty or changed while being read")
    return size, digest.hexdigest(), before


class PartStream:
    """Iterable request body with an explicit Content-Length and bounded reads."""
    def __init__(self, path, offset, size):
        self.path, self.offset, self.size = path, offset, size

    def __iter__(self):
        with self.path.open("rb") as source:
            source.seek(self.offset)
            remaining = self.size
            while remaining:
                block = source.read(min(remaining, BLOCK))
                require(block, "The upload file changed while reading a part")
                remaining -= len(block)
                yield block


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class FileUploader:
    def __init__(self, config):
        self.config = config
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.http_requests = 0
        self.parts_uploaded = 0
        self.complete_requested = False

    def request(self, method, path, body=None, length=None):
        auth = self.config["auth"]
        headers = {"Accept": "application/json", "X-Trace-Id": "upload-" + str(uuid.uuid4())}
        prefix = "/api/v1"
        if auth["type"] == "DEVICE":
            prefix += "/device"
            headers.update({"X-Device-Id": auth["deviceId"], "X-Device-Token": auth["deviceToken"]})
        else:
            headers["Authorization"] = "Bearer " + auth["accessToken"]
        if length is not None:
            data = body
            headers.update({"Content-Type": "application/octet-stream", "Content-Length": str(length)})
        else:
            data = None if body is None else json.dumps(body).encode()
            if data is not None:
                headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.config["baseUrl"] + prefix + path,
                                         data=data, headers=headers, method=method)
        self.http_requests += 1
        try:
            with self.opener.open(request, timeout=30) as response:
                envelope = json.load(response)
            require(isinstance(envelope, dict) and envelope.get("code") == "OK"
                    and isinstance(envelope.get("data"), dict), "Invalid upload API response")
            return envelope["data"]
        except urllib.error.HTTPError as failure:
            status = failure.code
            failure.close()
            if status >= 500 or status in (408, 429):
                raise UploadError("Temporary HTTP failure; rerun with the same configuration") from None
            raise UploadError("Upload API rejected the request with HTTP " + str(status)) from None
        except (urllib.error.URLError, OSError, ValueError):
            raise UploadError("Upload request did not complete; rerun with the same configuration") from None

    def save(self):
        save_private(self.config["stateFile"], self.state)

    def prepare_state(self):
        config = self.config
        size, sha256, self.stamp = file_hash(config["filePath"])
        source = {"path": str(config["filePath"]), "size": size, "sha256": sha256}
        body = {"requirementId": config["requirementId"], "executionId": config["executionId"],
                "filename": config["filePath"].name, "contentType": config["contentType"], "size": size}
        path = config["stateFile"]
        if path.exists():
            state = read_private(path)
            require(set(state) == {"formatVersion", "baseUrl", "authScope", "source", "request",
                                   "uploadId", "status", "versionId"}
                    and state.get("formatVersion") == 1 and state.get("baseUrl") == config["baseUrl"]
                    and state.get("authScope") == config["authScope"] and state.get("source") == source,
                    "File, backend or upload scope changed; keep the original checkpoint intact")
            request = state.get("request")
            require(isinstance(request, dict) and identifier(request.get("clientRequestId"))
                    and {k: v for k, v in request.items() if k != "clientRequestId"} == body,
                    "Upload request changed; keep the original checkpoint intact")
            require(state["uploadId"] is None or identifier(state["uploadId"]), "Invalid checkpoint upload identifier")
            require(state["status"] is None or state["status"] in STATUSES, "Invalid checkpoint status")
            require(state["versionId"] is None or identifier(state["versionId"]), "Invalid checkpoint version identifier")
            self.state = state
        else:
            body["clientRequestId"] = str(uuid.uuid4())
            self.state = {"formatVersion": 1, "baseUrl": config["baseUrl"], "authScope": config["authScope"],
                          "source": source, "request": body, "uploadId": None, "status": None, "versionId": None}
            self.save()  # The original request key exists on disk before the first POST.

    def part_hash(self, part_no, part_size, expected_size):
        require(file_stamp(self.config["filePath"]) == self.stamp, "The upload file changed during this run")
        stream = PartStream(self.config["filePath"], (part_no - 1) * part_size, expected_size)
        digest = hashlib.sha256()
        for block in stream:
            digest.update(block)
        require(file_stamp(self.config["filePath"]) == self.stamp, "The upload file changed during this run")
        return digest.hexdigest()

    def progress(self, value):
        body = self.state["request"]
        upload_id = value.get("uploadId")
        require(identifier(upload_id) and self.state["uploadId"] in (None, upload_id), "Unexpected upload identifier")
        require(all(value.get(k) == body[k] for k in ("requirementId", "executionId", "filename", "contentType", "size")),
                "Server upload scope does not match the checkpoint")
        part_size, count = value.get("partSize"), value.get("partCount")
        require(type(part_size) is int and part_size > 0 and type(count) is int and 1 <= count <= 10000
                and count == 1 + (body["size"] - 1) // part_size, "Invalid server part geometry")
        status, version = value.get("processingStatus"), value.get("versionId")
        require(status in STATUSES and (version is None or identifier(version)), "Invalid server upload status")
        require(status != "COMPLETED" or version is not None, "Completed upload is missing its version")
        parts = value.get("uploadedParts")
        require(isinstance(parts, list) and len(parts) <= count, "Invalid server upload progress")
        uploaded, uploaded_size = set(), 0
        for part in parts:
            require(isinstance(part, dict), "Invalid server part metadata")
            number = part.get("partNo")
            require(type(number) is int and 1 <= number <= count and number not in uploaded,
                    "Invalid server part number")
            expected = min(part_size, body["size"] - (number - 1) * part_size)
            require(type(part.get("size")) is int and part["size"] == expected
                    and part.get("sha256") == self.part_hash(number, part_size, expected),
                    "An existing server part differs from the local file; nothing was overwritten")
            uploaded.add(number)
            uploaded_size += expected
        require(type(value.get("uploadedSize")) is int and value["uploadedSize"] == uploaded_size,
                "Invalid server uploaded size")
        self.state.update(uploadId=upload_id, status=status, versionId=version)
        self.save()  # Save only whitelisted identifiers/status, never the raw provider response.
        return part_size, count, uploaded

    def summary(self, count, uploaded):
        return {"formatVersion": 1, "uploadId": self.state["uploadId"], "status": self.state["status"],
                "versionId": self.state["versionId"], "httpRequests": self.http_requests,
                "partsUploadedThisRun": self.parts_uploaded, "partsUploaded": self.parts_uploaded,
                "uploadedPartCount": len(uploaded), "partCount": count,
                "completeRequested": self.complete_requested,
                "completionAccepted": self.state["status"] in ("VERIFYING", "COMPLETED")}

    def run(self, max_parts=None):
        require(max_parts is None or type(max_parts) is int and max_parts >= 1, "max-parts must be positive")
        path = self.config["stateFile"]
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(path.parent, 0o700)
        descriptor = os.open(path.with_suffix(path.suffix + ".lock"), os.O_RDWR | os.O_CREAT, 0o600)
        with os.fdopen(descriptor, "w") as lock:
            try:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise UploadError("Another uploader owns this checkpoint") from None
            return self.run_locked(max_parts)

    def run_locked(self, max_parts):
        self.prepare_state()
        upload_id = self.state["uploadId"]
        value = self.request("POST", "/upload-sessions", self.state["request"]) if upload_id is None else \
            self.request("GET", "/upload-sessions/" + upload_id)
        part_size, count, uploaded = self.progress(value)
        require(self.state["status"] not in ("FAILED", "EXPIRED"), "Upload is failed or expired; no parts were changed")
        if self.state["status"] != "UPLOADING":
            return self.summary(count, uploaded)
        upload_id = self.state["uploadId"]
        for number in range(1, count + 1):
            if number in uploaded:
                continue
            if max_parts is not None and self.parts_uploaded >= max_parts:
                return self.summary(count, uploaded)
            expected = min(part_size, self.state["source"]["size"] - (number - 1) * part_size)
            sha256 = self.part_hash(number, part_size, expected)
            body = PartStream(self.config["filePath"], (number - 1) * part_size, expected)
            result = self.request("PUT", "/upload-sessions/" + upload_id + "/parts/" + str(number), body, expected)
            require(result.get("uploadId") == upload_id and type(result.get("partNo")) is int
                    and result["partNo"] == number and type(result.get("size")) is int
                    and result["size"] == expected and result.get("sha256") == sha256,
                    "The uploaded part acknowledgment does not match the local file")
            self.parts_uploaded += 1
            uploaded.add(number)
        # A bounded invocation deliberately stops before complete, even if its final part filled the session.
        if max_parts is not None:
            return self.summary(count, uploaded)
        size, sha256, _ = file_hash(self.config["filePath"])
        require(size == self.state["source"]["size"] and sha256 == self.state["source"]["sha256"],
                "The upload file changed before completion")
        self.complete_requested = True
        result = self.request("POST", "/upload-sessions/" + upload_id + "/complete")
        _, count, uploaded = self.progress(result)
        require(self.state["status"] in ("VERIFYING", "COMPLETED"), "The upload was not accepted for completion")
        return self.summary(count, uploaded)


def main():
    parser = argparse.ArgumentParser(description="Resume a private file upload; 202 means accepted, not finalized")
    parser.add_argument("--config", required=True)
    parser.add_argument("--max-parts", type=int, help="send at most N missing parts and stop before complete")
    arguments = parser.parse_args()
    config = load_config(arguments.config)
    result = FileUploader(config).run(arguments.max_parts)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except UploadError as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
    except Exception:
        # Provider bodies, request headers, file paths and credentials never enter a traceback/log.
        print("Cannot continue this upload; preserve the checkpoint and check private configuration", file=sys.stderr)
        sys.exit(1)
