"""Offline continuation tests using synthetic files, identities and an in-memory API."""
import base64
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
from urllib.parse import urlsplit
import uuid


SPEC = importlib.util.spec_from_file_location("uav_upload_under_test", Path(__file__).with_name("upload.py"))
upload = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(upload)


def jwt(subject, suffix="signature"):
    claims = base64.urlsafe_b64encode(json.dumps({"sub": subject}).encode()).decode().rstrip("=")
    return "e30." + claims + "." + suffix


class MemoryApi:
    """The fake retains acknowledged server data when a response deliberately disappears."""
    def __init__(self, config, part_size=16):
        self.config, self.part_size = config, part_size
        self.upload_id = str(uuid.uuid4())
        self.request_body = None
        self.parts = {}
        self.status = "UPLOADING"
        self.calls = []
        self.fail_create_once = self.fail_part_once = self.fail_complete_once = False
        self.progress_override = None

    def progress(self):
        body = self.request_body
        value = {"uploadId": self.upload_id, **{k: body[k] for k in
                 ("requirementId", "executionId", "filename", "contentType", "size")},
                 "partSize": self.part_size, "partCount": 1 + (body["size"] - 1) // self.part_size,
                 "processingStatus": self.status, "expiresAt": "2026-10-25T00:00:00Z",
                 "uploadedParts": [{"partNo": n, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                                   for n, data in sorted(self.parts.items())],
                 "uploadedSize": sum(map(len, self.parts.values())), "versionId": None,
                 "providerDebug": "synthetic-provider-secret-should-never-be-persisted"}
        if self.progress_override:
            self.progress_override(value)
        return value

    def open(self, request, timeout):
        path = urlsplit(request.full_url).path
        prefix = "/api/v1/device" if self.config["auth"]["type"] == "DEVICE" else "/api/v1"
        assert path.startswith(prefix + "/upload-sessions")
        path = path[len(prefix):]
        method = request.get_method()
        self.calls.append((method, path))
        if method == "POST" and path == "/upload-sessions":
            body = json.loads(request.data)
            if self.request_body is not None:
                assert self.request_body == body, "Create retries must reuse the exact original request"
            self.request_body = copy.deepcopy(body)
            if self.fail_create_once:
                self.fail_create_once = False
                raise urllib.error.URLError("synthetic-provider-secret-response-lost")
            result = self.progress()
        elif method == "GET":
            assert path == "/upload-sessions/" + self.upload_id
            result = self.progress()
        elif method == "PUT":
            number = int(path.rsplit("/", 1)[1])
            data = b"".join(request.data)
            assert len(data) == int(request.get_header("Content-length"))
            if number in self.parts:
                assert self.parts[number] == data, "A part cannot replace accepted bytes"
            self.parts[number] = data
            if self.fail_part_once:
                self.fail_part_once = False
                raise urllib.error.URLError("synthetic-provider-secret-response-lost")
            result = {"uploadId": self.upload_id, "partNo": number, "size": len(data),
                      "sha256": hashlib.sha256(data).hexdigest()}
        else:
            assert method == "POST" and path == "/upload-sessions/" + self.upload_id + "/complete"
            assert len(self.parts) == self.progress()["partCount"]
            self.status = "VERIFYING"
            if self.fail_complete_once:
                self.fail_complete_once = False
                raise urllib.error.URLError("synthetic-provider-secret-response-lost")
            result = self.progress()
        return io.BytesIO(json.dumps({"code": "OK", "data": result}).encode())


class UploadRecoveryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="uav-file-upload-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.local = self.root / ".local"
        self.local.mkdir(mode=0o700)
        for name, value in (("ROOT", self.root), ("LOCAL", self.local)):
            patch = mock.patch.object(upload, name, value)
            patch.start()
            self.addCleanup(patch.stop)
        no_network = mock.patch("urllib.request.OpenerDirector.open",
                                side_effect=AssertionError("Offline tests cannot perform HTTP"))
        no_network.start()
        self.addCleanup(no_network.stop)
        self.file = self.root / "sample.png"
        self.content = b"\x89PNG\r\n\x1a\n" + bytes(range(96))
        self.file.write_bytes(self.content)
        self.actor = str(uuid.uuid4())
        self.secret = "synthetic-private-signature"
        self.token = jwt(self.actor, self.secret)
        self.path = self.local / "config.json"
        self.raw = {"formatVersion": 1, "baseUrl": "http://127.0.0.1:8080", "filePath": str(self.file),
                    "requirementId": str(uuid.uuid4()), "executionId": str(uuid.uuid4()),
                    "stateFile": str(self.local / "upload-state.json"),
                    "auth": {"type": "USER", "accessToken": self.token}, "partSize": 3}
        upload.save_private(self.path, self.raw)
        self.config = upload.load_config(self.path)
        self.server = MemoryApi(self.config)

    def client(self):
        client = upload.FileUploader(upload.load_config(self.path))
        client.opener.open = mock.Mock(side_effect=self.server.open)
        return client

    def state(self):
        return json.loads(self.config["stateFile"].read_text())

    def assert_safe(self, output=""):
        state = self.config["stateFile"].read_text()
        for secret in (self.token, self.secret, self.raw["auth"].get("deviceToken", "not-a-real-token"),
                       "synthetic-provider-secret"):
            self.assertNotIn(secret, state)
            self.assertNotIn(secret, output)
        for field in ("accessToken", "deviceToken", "Authorization", "providerDebug"):
            self.assertNotIn(field, state)
        self.assertEqual(0o600, stat.S_IMODE(self.config["stateFile"].stat().st_mode))

    def test_new_process_resumes_only_server_missing_parts_using_original_geometry(self):
        first = self.client().run(max_parts=1)
        self.assertEqual(("UPLOADING", 1, 2, False),
                         (first["status"], first["partsUploadedThisRun"], first["httpRequests"], first["completeRequested"]))
        original = self.state()
        self.assertEqual(1, len(self.server.parts))
        self.assertEqual(16, self.server.progress()["partSize"])
        # A new client ignores a changed local partSize; the server session remains authoritative.
        self.raw["partSize"] = 512
        upload.save_private(self.path, self.raw)
        before = len(self.server.calls)
        result = self.client().run()
        self.assertEqual("GET", self.server.calls[before][0])
        self.assertEqual("VERIFYING", result["status"])
        self.assertTrue(result["completionAccepted"])
        self.assertIsNone(result["versionId"])
        self.assertEqual(6, result["partsUploadedThisRun"])
        self.assertEqual(8, result["httpRequests"])
        self.assertEqual(original["request"], self.state()["request"])
        self.assertEqual(self.content, b"".join(self.server.parts[n] for n in sorted(self.server.parts)))
        self.assertEqual(1, self.server.calls.count(("PUT", "/upload-sessions/" + self.server.upload_id + "/parts/1")))
        self.assert_safe(json.dumps(result))

    def test_create_response_loss_reuses_same_persisted_client_request_id(self):
        self.server.fail_create_once = True
        with self.assertRaises(upload.UploadError):
            self.client().run()
        initial = self.state()
        self.assertIsNone(initial["uploadId"])
        self.assertEqual(initial["request"], self.server.request_body)
        result = self.client().run()
        self.assertEqual("VERIFYING", result["status"])
        self.assertEqual(2, self.server.calls.count(("POST", "/upload-sessions")))
        self.assertEqual(initial["request"]["clientRequestId"], self.state()["request"]["clientRequestId"])
        self.assert_safe()

    def test_part_response_loss_uses_get_and_never_resends_the_accepted_part(self):
        self.server.fail_part_once = True
        with self.assertRaises(upload.UploadError):
            self.client().run()
        self.assertEqual(1, len(self.server.parts))
        self.assertEqual(self.server.upload_id, self.state()["uploadId"])
        before = len(self.server.calls)
        result = self.client().run()
        self.assertEqual(("GET", "/upload-sessions/" + self.server.upload_id), self.server.calls[before])
        self.assertEqual(6, result["partsUploadedThisRun"])
        self.assertEqual(1, self.server.calls.count(("PUT", "/upload-sessions/" + self.server.upload_id + "/parts/1")))
        self.assert_safe()

    def test_completion_response_loss_observes_frozen_session_without_duplicate_complete(self):
        self.server.fail_complete_once = True
        with self.assertRaises(upload.UploadError):
            self.client().run()
        self.assertEqual("VERIFYING", self.server.status)
        before = len(self.server.calls)
        result = self.client().run()
        self.assertEqual([( "GET", "/upload-sessions/" + self.server.upload_id)], self.server.calls[before:])
        self.assertEqual(0, result["partsUploadedThisRun"])
        self.assertFalse(result["completeRequested"])
        self.assertTrue(result["completionAccepted"])
        self.assert_safe()

    def test_changed_local_file_or_execution_is_rejected_before_any_http(self):
        self.client().run(max_parts=1)
        original = self.config["stateFile"].read_bytes()
        self.file.write_bytes(self.content[:-1] + b"!")
        client = self.client()
        with self.assertRaises(upload.UploadError):
            client.run()
        client.opener.open.assert_not_called()
        self.assertEqual(original, self.config["stateFile"].read_bytes())
        self.file.write_bytes(self.content)
        self.raw["executionId"] = str(uuid.uuid4())
        upload.save_private(self.path, self.raw)
        client = self.client()
        with self.assertRaises(upload.UploadError):
            client.run()
        client.opener.open.assert_not_called()

    def test_existing_server_part_size_or_hash_conflict_never_overwrites_remote_content(self):
        self.client().run(max_parts=1)
        original_state = self.config["stateFile"].read_bytes()
        original_parts = copy.deepcopy(self.server.parts)
        for bad_field in ("size", "sha256"):
            with self.subTest(field=bad_field):
                def corrupt(value):
                    value["uploadedParts"][0][bad_field] = 999 if bad_field == "size" else "0" * 64
                self.server.progress_override = corrupt
                before = len(self.server.calls)
                with self.assertRaises(upload.UploadError):
                    self.client().run()
                self.assertEqual([("GET", "/upload-sessions/" + self.server.upload_id)], self.server.calls[before:])
                self.assertEqual(original_parts, self.server.parts)
                self.assertEqual(original_state, self.config["stateFile"].read_bytes())

    def test_refreshed_user_token_can_resume_but_another_identity_cannot(self):
        self.client().run(max_parts=1)
        self.raw["auth"]["accessToken"] = jwt(self.actor, "refreshed-private-signature")
        upload.save_private(self.path, self.raw)
        self.assertEqual("VERIFYING", self.client().run()["status"])
        self.raw["auth"]["accessToken"] = jwt(str(uuid.uuid4()))
        upload.save_private(self.path, self.raw)
        client = self.client()
        with self.assertRaises(upload.UploadError):
            client.run()
        client.opener.open.assert_not_called()
        self.assert_safe()

    def test_device_credentials_stay_in_headers_while_checkpoint_and_summary_are_safe(self):
        self.raw["auth"] = {"type": "DEVICE", "deviceId": str(uuid.uuid4()), "deviceToken": "ab" * 32}
        upload.save_private(self.path, self.raw)
        self.config = upload.load_config(self.path)
        self.server = MemoryApi(self.config)
        client = self.client()
        result = client.run(max_parts=1)
        for call in client.opener.open.call_args_list:
            request = call.args[0]
            self.assertEqual(self.raw["auth"]["deviceToken"], request.get_header("X-device-token"))
            self.assertNotIn(self.raw["auth"]["deviceToken"], request.full_url)
            self.assertTrue(urlsplit(request.full_url).path.startswith("/api/v1/device/"))
        self.assert_safe(json.dumps(result))

    def test_http_error_details_are_closed_and_never_reported_or_persisted(self):
        for status in (401, 307, 503):
            with self.subTest(status=status):
                client = self.client()
                response = io.BytesIO((self.token + self.secret).encode())
                error = urllib.error.HTTPError("http://provider/" + self.token, status, self.secret,
                                                {"X-private": self.token}, response)
                client.opener.open.side_effect = error
                with self.assertRaises(upload.UploadError) as raised:
                    client.run()
                self.assertTrue(response.closed)
                self.assert_safe(str(raised.exception))

    def test_cli_partial_summary_is_safe_json_and_private_configuration_is_required(self):
        output = io.StringIO()
        original = upload.FileUploader

        def uploader(config):
            client = original(config)
            client.opener.open = mock.Mock(side_effect=self.server.open)
            return client

        with mock.patch.object(upload, "FileUploader", side_effect=uploader), \
                mock.patch.object(sys, "argv", ["upload.py", "--config", str(self.path), "--max-parts", "1"]), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            upload.main()
        value = json.loads(output.getvalue())
        self.assertEqual("UPLOADING", value["status"])
        self.assertFalse(value["completeRequested"])
        self.assert_safe(output.getvalue())
        self.path.chmod(0o644)
        with self.assertRaises(upload.UploadError):
            upload.load_config(self.path)

    def test_part_stream_bounds_each_read_and_never_reads_into_the_next_part(self):
        large = self.root / "large.pdf"
        content = bytes(range(256)) * 1024
        large.write_bytes(content)
        chunks = list(upload.PartStream(large, 13, 2 * upload.BLOCK + 7))
        self.assertTrue(all(0 < len(chunk) <= upload.BLOCK for chunk in chunks))
        self.assertEqual(content[13:13 + 2 * upload.BLOCK + 7], b"".join(chunks))


if __name__ == "__main__":
    unittest.main()
