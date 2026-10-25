#!/usr/bin/env python3
"""A small HTTP device simulator. It never controls aircraft or stores tokens in state/logs."""
import argparse
import concurrent.futures
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid

ROOT = Path(__file__).resolve().parents[1]
LOCAL = (ROOT / ".local").resolve()
TOKEN = re.compile(r"[0-9a-f]{64}\Z")


class SimulationError(Exception):
    pass


def local_path(value):
    path = Path(value)
    path = (ROOT / path).resolve() if not path.is_absolute() else path.resolve()
    if not path.is_relative_to(LOCAL):
        raise SimulationError("Private configuration and state must be under .local/")
    return path


def private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=".swarm-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump(value, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def canonical_id(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def integer(value, name, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise SimulationError("Invalid " + name)
    return value


def load_config(path):
    path = local_path(path)
    if not path.is_file() or path.stat().st_mode & 0o077:
        raise SimulationError("Use a private 0600 configuration file under .local/")
    try:
        config = json.loads(path.read_text())
    except (OSError, ValueError):
        raise SimulationError("Cannot read simulator configuration") from None
    if not isinstance(config, dict) or config.get("formatVersion") != 1:
        raise SimulationError("Unsupported simulator configuration")
    count = integer(config.get("count", 3), "device count (3 to 5)", 3, 5)
    devices = config.get("devices")
    if not isinstance(devices, list) or len(devices) != count:
        raise SimulationError("Device list must contain exactly count devices")
    seen = set()
    for device in devices:
        if not isinstance(device, dict) or not all(canonical_id(device.get(key)) for key in
                                                  ("deviceId", "executionId", "requirementId")):
            raise SimulationError("Invalid device, execution or requirement identifier")
        if device["deviceId"] in seen or not TOKEN.fullmatch(str(device.get("deviceToken", ""))):
            raise SimulationError("Each device requires a unique identifier and a valid private token")
        seen.add(device["deviceId"])
    url = urlsplit(config.get("baseUrl", ""))
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
        raise SimulationError("baseUrl must be an HTTP server origin without credentials")
    config["baseUrl"] = config["baseUrl"].rstrip("/")
    interval = config.get("intervalSeconds", 5)
    if isinstance(interval, bool) or not isinstance(interval, (int, float)) or not 0.05 <= interval <= 3600:
        raise SimulationError("Invalid intervalSeconds")
    config["intervalSeconds"] = interval
    config["cycles"] = integer(config.get("cycles", 12), "cycles", 1, 100000)
    config["progressStep"] = integer(config.get("progressStep", 10), "progressStep", 1, 100)
    for key, default in (("uploadEveryCycles", 4), ("repeatEveryCycles", 2)):
        config[key] = integer(config.get(key, default), key, 0, 100000)
    outages = config.get("networkOutageCycles", [])
    if not isinstance(outages, list) or any(type(n) is not int or n < 1 for n in outages):
        raise SimulationError("Invalid networkOutageCycles")
    config["networkOutageCycles"] = set(outages)
    config["completeUploads"] = config.get("completeUploads", True)
    if type(config["completeUploads"]) is not bool:
        raise SimulationError("Invalid completeUploads")
    config["stateDirectory"] = local_path(config.get("stateDirectory", ".local/simulator/state"))
    png = Path(config.get("pngPath", "examples/files/inspection-sample.png"))
    png = (ROOT / png).resolve() if not png.is_absolute() else png.resolve()
    try:
        content = png.read_bytes()
    except OSError:
        raise SimulationError("Cannot read configured PNG fixture") from None
    if not content.startswith(b"\x89PNG\r\n\x1a\n") or len(content) > 8 * 1024 * 1024:
        raise SimulationError("Use a PNG fixture no larger than 8 MiB")
    config["pngBytes"] = content
    return config


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class DeviceWorker:
    def __init__(self, config, device, index):
        self.config, self.device, self.index = config, device, index
        self.path = config["stateDirectory"] / (device["deviceId"] + ".json")
        self.stats = {"index": index, "httpRequests": 0, "reportsAccepted": 0, "duplicates": 0,
                      "uploadsSubmitted": 0, "partsUploaded": 0, "simulatedOfflineCycles": 0, "transientFailures": 0}
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        if self.path.exists():
            if self.path.stat().st_mode & 0o077:
                raise SimulationError("Simulator state must have mode 0600")
            try:
                self.state = json.loads(self.path.read_text())
            except (OSError, ValueError):
                raise SimulationError("Cannot read persisted simulator state") from None
            if self.state.get("deviceId") != device["deviceId"] or self.state.get("formatVersion") != 1:
                raise SimulationError("Persisted simulator state does not match the device")
        else:
            self.state = {"formatVersion": 1, "deviceId": device["deviceId"], "progress": 0,
                          "outstandingReport": None, "outstandingUpload": None}
        # Old outstanding payloads retain their old bootId/seq until acknowledged.
        self.state.update(activeBootId=str(uuid.uuid4()), nextSeq=0)
        self.save()

    def save(self):
        private_json(self.path, self.state)

    def request(self, method, path, body=None, raw=False):
        data = body if raw else (None if body is None else json.dumps(body).encode())
        headers = {"X-Device-Id": self.device["deviceId"], "X-Device-Token": self.device["deviceToken"],
                   "Accept": "application/json", "X-Trace-Id": "sim-" + str(uuid.uuid4())}
        if data is not None:
            headers["Content-Type"] = "application/octet-stream" if raw else "application/json"
        request = urllib.request.Request(self.config["baseUrl"] + "/api/v1/device" + path,
                                         data=data, headers=headers, method=method)
        self.stats["httpRequests"] += 1
        try:
            with self.opener.open(request, timeout=20) as response:
                result = json.load(response)
                if result.get("code") != "OK" or not isinstance(result.get("data"), dict):
                    raise SimulationError("Invalid device API response")
                return result["data"]
        except urllib.error.HTTPError as failure:
            # Raw messages/URLs/headers are deliberately excluded from diagnostics.
            status = failure.code
            failure.close()
            if status >= 500 or status in (408, 429):
                self.stats["transientFailures"] += 1
                return None
            raise SimulationError("Device API rejected a request with HTTP " + str(status)) from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            self.stats["transientFailures"] += 1
            return None

    def report(self, cycle):
        pending = self.state.get("outstandingReport")
        if pending is None:
            sequence = self.state["nextSeq"]
            pending = {"bootId": self.state["activeBootId"], "seq": sequence,
                       "eventId": str(uuid.uuid4()), "executionId": self.device["executionId"],
                       "type": "HEARTBEAT" if cycle % 2 else "PROGRESS"}
            if pending["type"] == "PROGRESS":
                pending["progress"] = min(100, self.state["progress"] + self.config["progressStep"])
            self.state["outstandingReport"] = pending
            self.state["nextSeq"] = sequence + 1
            self.save()  # Persist before the first network write, including outage simulation.
        if cycle in self.config["networkOutageCycles"]:
            return
        result = self.request("POST", "/reports", pending)
        if result is None:
            return
        if result.get("eventId") != pending["eventId"] or result.get("bootId") != pending["bootId"] or result.get("seq") != pending["seq"]:
            raise SimulationError("Report acknowledgement does not match the durable request")
        self.stats["reportsAccepted"] += 1
        self.stats["duplicates"] += int(result.get("duplicate") is True)
        self.state["progress"] = max(self.state["progress"], result.get("executionProgress", 0))
        self.state["lastAck"] = {"payload": pending, "result": result}
        self.state["outstandingReport"] = None
        self.save()
        repeat = self.config["repeatEveryCycles"]
        if repeat and cycle % repeat == 0:
            self.state["outstandingReport"] = pending
            self.save()
            replay = self.request("POST", "/reports", pending)
            if replay is not None:
                if replay.get("duplicate") is not True or replay.get("id") != result.get("id") or replay.get("receivedAt") != result.get("receivedAt"):
                    raise SimulationError("Replay did not return the original acknowledgement")
                self.stats["duplicates"] += 1
                self.state["outstandingReport"] = None
                self.save()

    def upload(self, cycle):
        pending = self.state.get("outstandingUpload")
        interval = self.config["uploadEveryCycles"]
        if pending is None and interval and cycle % interval == 0:
            content = self.config["pngBytes"]
            pending = {"body": {"clientRequestId": str(uuid.uuid4()), "requirementId": self.device["requirementId"],
                       "executionId": self.device["executionId"], "filename": "swarm-sample.png",
                       "contentType": "image/png", "size": len(content)},
                       "sha256": hashlib.sha256(content).hexdigest(), "stage": "CREATE"}
            self.state["outstandingUpload"] = pending
            self.save()
        if pending is None or cycle in self.config["networkOutageCycles"]:
            return
        if hashlib.sha256(self.config["pngBytes"]).hexdigest() != pending["sha256"]:
            raise SimulationError("PNG changed while a durable upload was outstanding")
        if pending["stage"] == "CREATE":
            result = self.request("POST", "/upload-sessions", pending["body"])
            if result is None:
                return
            if result.get("partCount") != 1:
                raise SimulationError("This simulator requires a single-part upload")
            pending.update(uploadId=result["uploadId"], stage="PART")
            self.save()
        if pending["stage"] == "PART":
            result = self.request("PUT", "/upload-sessions/" + pending["uploadId"] + "/parts/1",
                                  self.config["pngBytes"], raw=True)
            if result is None:
                return
            self.stats["partsUploaded"] += 1
            pending["stage"] = "COMPLETE" if self.config["completeUploads"] else "DONE"
            self.save()
        if pending["stage"] == "COMPLETE":
            result = self.request("POST", "/upload-sessions/" + pending["uploadId"] + "/complete")
            if result is None:
                return
            self.stats["uploadsSubmitted"] += 1
            pending["stage"] = "DONE"
            self.save()
        if pending["stage"] == "DONE":
            self.state["lastUpload"] = {"uploadId": pending["uploadId"], "clientRequestId": pending["body"]["clientRequestId"],
                                         "sha256": pending["sha256"], "completeRequested": self.config["completeUploads"]}
            self.state["outstandingUpload"] = None
            self.save()

    def run(self):
        try:
            for cycle in range(1, self.config["cycles"] + 1):
                if cycle in self.config["networkOutageCycles"]:
                    self.stats["simulatedOfflineCycles"] += 1
                self.report(cycle)
                self.upload(cycle)
                if cycle < self.config["cycles"]:
                    time.sleep(self.config["intervalSeconds"])
            self.stats["pendingReport"] = self.state.get("outstandingReport") is not None
            self.stats["pendingUpload"] = self.state.get("outstandingUpload") is not None
            self.stats["passed"] = not self.stats["pendingReport"] and not self.stats["pendingUpload"]
        except SimulationError as failure:
            self.stats.update(passed=False, failure=str(failure))
        return self.stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, help="Private 0600 configuration under .local/")
    parser.add_argument("--cycles", type=int, help="Override configured cycle count")
    parser.add_argument("--validate-only", action="store_true", help="Validate without making HTTP requests")
    args = parser.parse_args()
    config = load_config(args.config)
    if args.cycles is not None:
        config["cycles"] = integer(args.cycles, "cycles", 1, 100000)
    if args.validate_only:
        print("[PASS] Private simulator configuration is valid; devices=" + str(config["count"]))
        return
    state = config["stateDirectory"]
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(state, 0o700)
    descriptor = os.open(state / ".run.lock", os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SimulationError("Another simulator owns this state directory") from None
        workers = [DeviceWorker(config, device, index + 1) for index, device in enumerate(config["devices"])]
        with concurrent.futures.ThreadPoolExecutor(max_workers=config["count"]) as pool:
            results = list(pool.map(lambda worker: worker.run(), workers))
        summary = {"formatVersion": 1, "deviceCount": config["count"], "cycles": config["cycles"],
                   "passed": all(item["passed"] for item in results), "devices": results}
        private_json(state / "summary.json", summary)
    for result in results:
        print("Device " + str(result["index"]) + ": " + ("PASS" if result["passed"] else "PENDING/FAILED"))
    if not summary["passed"]:
        raise SimulationError("Some devices still have durable pending work or a rejected request; inspect private state")


if __name__ == "__main__":
    try:
        main()
    except (SimulationError, OSError, ValueError, KeyError, TypeError):
        print("[FAIL] Simulator could not finish; inspect its private configuration/state and API availability", file=sys.stderr)
        sys.exit(1)
