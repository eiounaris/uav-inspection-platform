"""Offline recovery tests. All credentials, files and API replies below are synthetic."""

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
import threading
import unittest
from unittest import mock
import urllib.error
import uuid


SPEC = importlib.util.spec_from_file_location("uav_swarm_under_test", Path(__file__).with_name("swarm.py"))
swarm = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(swarm)


class SwarmRecoveryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="uav-swarm-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.local = self.root / ".local"
        self.local.mkdir(mode=0o700)
        for name, value in (("ROOT", self.root), ("LOCAL", self.local)):
            patch = mock.patch.object(swarm, name, value)
            patch.start()
            self.addCleanup(patch.stop)
        # Even a newly added request path must not accidentally contact a real backend.
        no_network = mock.patch("urllib.request.OpenerDirector.open",
                                side_effect=AssertionError("Offline tests cannot perform HTTP"))
        no_network.start()
        self.addCleanup(no_network.stop)
        self.png = b"\x89PNG\r\n\x1a\nsynthetic-offline-fixture"
        sample = self.root / "examples/sample.png"
        sample.parent.mkdir()
        sample.write_bytes(self.png)

    def configuration(self, count=3, name="state", **changes):
        devices = [{"deviceId": str(uuid.uuid4()),
                    "deviceToken": hashlib.sha256(("synthetic-private-device-" + str(index)).encode()).hexdigest(),
                    "executionId": str(uuid.uuid4()), "requirementId": str(uuid.uuid4())}
                   for index in range(count)]
        raw = {"formatVersion": 1, "baseUrl": "http://127.0.0.1:8080", "count": count,
               "devices": devices, "stateDirectory": ".local/" + name,
               "pngPath": "examples/sample.png", "cycles": 1, "intervalSeconds": 0.05,
               "progressStep": 10, "repeatEveryCycles": 0, "uploadEveryCycles": 0,
               "networkOutageCycles": [], "completeUploads": True}
        raw.update(changes)
        path = self.local / (name + "-config.json")
        swarm.private_json(path, raw)
        return swarm.load_config(path), path, raw

    def worker(self, config):
        return swarm.DeviceWorker(config, config["devices"][0], 1)

    def persisted(self, worker):
        return json.loads(worker.path.read_text())

    def assert_private_state(self, config, output=""):
        files = list(config["stateDirectory"].glob("*.json"))
        self.assertTrue(files)
        for path in files:
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            serialized = path.read_text()
            self.assertNotIn("deviceToken", serialized)
            for device in config["devices"]:
                self.assertNotIn(device["deviceToken"], serialized)
                self.assertNotIn(device["deviceToken"], output)

    @staticmethod
    def acknowledgment(payload, duplicate=False):
        return {"id": "30000000-0000-4000-8000-000000000001",
                "droneId": "20000000-0000-4000-8000-000000000001",
                "eventId": payload["eventId"], "bootId": payload["bootId"], "seq": payload["seq"],
                "executionId": payload["executionId"], "type": payload["type"],
                "progress": payload.get("progress"), "executionProgress": payload.get("progress", 0),
                "receivedAt": "2026-10-24T00:00:00.123Z", "duplicate": duplicate}

    def test_three_and_five_device_configs_require_exact_count_and_private_permissions(self):
        for count in (3, 5):
            with self.subTest(count=count):
                config, path, raw = self.configuration(count, "config-" + str(count))
                self.assertEqual(count, len(config["devices"]))
                self.assertEqual(count, config["count"])
                invalid = copy.deepcopy(raw)
                invalid["devices"].pop()
                swarm.private_json(path, invalid)
                with self.assertRaises(swarm.SimulationError):
                    swarm.load_config(path)
                for unsupported in (2, 6):
                    invalid = copy.deepcopy(raw)
                    invalid["count"] = unsupported
                    swarm.private_json(path, invalid)
                    with self.assertRaises(swarm.SimulationError):
                        swarm.load_config(path)
                swarm.private_json(path, raw)
                path.chmod(0o644)
                with self.assertRaises(swarm.SimulationError):
                    swarm.load_config(path)

    def test_main_runs_three_or_five_workers_concurrently_and_emits_no_credentials(self):
        for count in (3, 5):
            with self.subTest(count=count):
                config, path, _ = self.configuration(count, "parallel-" + str(count))
                barrier = threading.Barrier(count)
                threads, identities = set(), set()
                mutex = threading.Lock()

                def report(worker, cycle):
                    self.assertEqual(1, cycle)
                    # A serial executor or a pool smaller than count cannot cross this barrier.
                    barrier.wait(timeout=5)
                    with mutex:
                        threads.add(threading.get_ident())
                        identities.add(worker.device["deviceId"])
                    worker.stats["reportsAccepted"] += 1

                output = io.StringIO()
                with mock.patch.object(swarm.DeviceWorker, "report", report), \
                        mock.patch.object(sys, "argv", ["swarm.py", "--config", str(path)]), \
                        contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                    swarm.main()
                summary = json.loads((config["stateDirectory"] / "summary.json").read_text())
                self.assertTrue(summary["passed"])
                self.assertEqual(count, len(threads))
                self.assertEqual({device["deviceId"] for device in config["devices"]}, identities)
                self.assertEqual(count, len(summary["devices"]))
                boots = {json.loads((config["stateDirectory"] / (device["deviceId"] + ".json")).read_text())["activeBootId"]
                         for device in config["devices"]}
                self.assertEqual(count, len(boots))
                self.assert_private_state(config, output.getvalue())

    def test_report_reboot_preserves_old_payload_then_starts_new_boot_at_sequence_zero(self):
        config, _, _ = self.configuration()
        first = self.worker(config)
        before = first.state["activeBootId"]

        def lose_ack(method, path, body):
            self.assertEqual(("POST", "/reports"), (method, path))
            self.assertEqual(body, self.persisted(first)["outstandingReport"])
            return None  # The server may have committed even though its response was lost.

        with mock.patch.object(first, "request", side_effect=lose_ack):
            first.report(2)
        original = self.persisted(first)["outstandingReport"]
        self.assertEqual(before, original["bootId"])
        self.assertEqual(0, original["seq"])
        restarted = self.worker(config)
        new_boot = restarted.state["activeBootId"]
        self.assertNotEqual(before, new_boot)
        sent = []

        def respond(method, path, body):
            sent.append(copy.deepcopy(body))
            return self.acknowledgment(body, duplicate=len(sent) == 1)

        with mock.patch.object(restarted, "request", side_effect=respond):
            restarted.report(1)
            self.assertEqual(original, sent[0])
            self.assertIsNone(restarted.state["outstandingReport"])
            restarted.report(2)
        self.assertEqual(new_boot, sent[1]["bootId"])
        self.assertEqual(0, sent[1]["seq"])
        self.assertNotEqual(original["eventId"], sent[1]["eventId"])
        self.assertEqual(original["progress"] + config["progressStep"], sent[1]["progress"])
        self.assertEqual(1, restarted.stats["duplicates"])
        self.assert_private_state(config)

    def test_lost_explicit_replay_response_remains_pending_across_restart(self):
        config, _, _ = self.configuration(repeatEveryCycles=2)
        worker = self.worker(config)
        sent = []

        def respond(method, path, body):
            sent.append(copy.deepcopy(body))
            return self.acknowledgment(body) if len(sent) == 1 else None

        with mock.patch.object(worker, "request", side_effect=respond):
            worker.report(2)
        self.assertEqual(sent[0], sent[1])
        self.assertEqual(sent[0], self.persisted(worker)["outstandingReport"])
        original_ack = self.persisted(worker)["lastAck"]["result"]
        restarted = self.worker(config)

        def replay(method, path, body):
            self.assertEqual(sent[0], body)
            result = dict(original_ack)
            result["duplicate"] = True
            return result

        with mock.patch.object(restarted, "request", side_effect=replay):
            restarted.report(1)
        self.assertIsNone(self.persisted(restarted)["outstandingReport"])
        self.assertEqual(original_ack["id"], restarted.state["lastAck"]["result"]["id"])
        self.assertEqual(original_ack["receivedAt"], restarted.state["lastAck"]["result"]["receivedAt"])
        self.assert_private_state(config)

    def test_upload_restarts_from_each_checkpoint_without_replacing_client_request_or_session(self):
        for lost_stage in ("CREATE", "PART", "COMPLETE"):
            with self.subTest(stage=lost_stage):
                config, _, _ = self.configuration(name="upload-" + lost_stage, uploadEveryCycles=1)
                worker = self.worker(config)
                upload_id = str(uuid.uuid4())
                pending_body = None

                def stage_request(method, path, body=None, raw=False):
                    pending = self.persisted(worker)["outstandingUpload"]
                    self.assertEqual(pending["stage"], "CREATE" if path == "/upload-sessions" else
                                     "PART" if method == "PUT" else "COMPLETE")
                    if pending["stage"] == lost_stage:
                        return None
                    if method == "PUT":
                        self.assertTrue(raw)
                        self.assertEqual(self.png, body)
                        return {"uploadId": upload_id, "partNo": 1, "size": len(body),
                                "sha256": hashlib.sha256(body).hexdigest()}
                    return {"uploadId": upload_id, "partCount": 1}

                with mock.patch.object(worker, "request", side_effect=stage_request):
                    worker.upload(1)
                pending = self.persisted(worker)["outstandingUpload"]
                self.assertEqual(lost_stage, pending["stage"])
                pending_body = copy.deepcopy(pending["body"])
                restarted = self.worker(config)
                calls = []

                def finish(method, path, body=None, raw=False):
                    calls.append((method, path))
                    self.assertEqual(pending_body, self.persisted(restarted)["outstandingUpload"]["body"])
                    if path == "/upload-sessions":
                        self.assertEqual(pending_body, body)
                        return {"uploadId": upload_id, "partCount": 1}
                    if method == "PUT":
                        self.assertTrue(raw)
                        self.assertEqual(self.png, body)
                        return {"uploadId": upload_id, "partNo": 1, "sha256": hashlib.sha256(body).hexdigest()}
                    # Acceptance is asynchronous: it does not claim a fixed or anchored version exists.
                    return {"uploadId": upload_id, "processingStatus": "VERIFYING", "versionId": None}

                with mock.patch.object(restarted, "request", side_effect=finish):
                    restarted.upload(2)
                expected = [("POST", "/upload-sessions"),
                            ("PUT", "/upload-sessions/" + upload_id + "/parts/1"),
                            ("POST", "/upload-sessions/" + upload_id + "/complete")]
                self.assertEqual(expected[("CREATE", "PART", "COMPLETE").index(lost_stage):], calls)
                restored = self.persisted(restarted)
                self.assertIsNone(restored["outstandingUpload"])
                self.assertEqual(pending_body["clientRequestId"], restored["lastUpload"]["clientRequestId"])
                self.assertEqual(upload_id, restored["lastUpload"]["uploadId"])
                self.assertEqual(1, restarted.stats["uploadsSubmitted"])
                self.assertEqual(0 if lost_stage == "COMPLETE" else 1, restarted.stats["partsUploaded"])
                self.assertNotIn("uploadsCompleted", restarted.stats)
                self.assert_private_state(config)

    def test_accepted_create_lost_before_checkpoint_save_reuses_original_request(self):
        config, _, _ = self.configuration(uploadEveryCycles=1)
        worker = self.worker(config)
        upload_id = str(uuid.uuid4())
        original_save = worker.save
        original_request = []

        def fail_checkpoint():
            if worker.state.get("outstandingUpload", {}).get("stage") == "PART":
                raise OSError("Synthetic checkpoint write failure")
            original_save()

        def created(method, path, body=None, raw=False):
            original_request.append(copy.deepcopy(body))
            return {"uploadId": upload_id, "partCount": 1}

        with mock.patch.object(worker, "save", side_effect=fail_checkpoint), \
                mock.patch.object(worker, "request", side_effect=created):
            with self.assertRaises(OSError):
                worker.upload(1)
        self.assertEqual("CREATE", self.persisted(worker)["outstandingUpload"]["stage"])
        restarted = self.worker(config)

        def resume(method, path, body=None, raw=False):
            if path == "/upload-sessions":
                self.assertEqual(original_request[0], body)
                return {"uploadId": upload_id, "partCount": 1}
            return {"uploadId": upload_id, "processingStatus": "VERIFYING"}

        with mock.patch.object(restarted, "request", side_effect=resume):
            restarted.upload(2)
        self.assertEqual(original_request[0]["clientRequestId"], restarted.state["lastUpload"]["clientRequestId"])
        self.assertEqual(upload_id, restarted.state["lastUpload"]["uploadId"])
        self.assert_private_state(config)

    def test_changed_file_is_rejected_before_retrying_existing_upload_identity(self):
        config, _, _ = self.configuration(uploadEveryCycles=1)
        worker = self.worker(config)
        with mock.patch.object(worker, "request", return_value=None):
            worker.upload(1)
        original = self.persisted(worker)["outstandingUpload"]
        changed = dict(config, pngBytes=self.png + b"changed")
        restarted = self.worker(changed)
        with mock.patch.object(restarted, "request") as request:
            with self.assertRaises(swarm.SimulationError):
                restarted.upload(2)
        request.assert_not_called()
        self.assertEqual(original, self.persisted(restarted)["outstandingUpload"])
        self.assert_private_state(config)

    def test_simulated_outage_persists_both_requests_before_attempting_network(self):
        config, _, _ = self.configuration(uploadEveryCycles=1, networkOutageCycles=[1])
        worker = self.worker(config)
        with mock.patch.object(worker, "request") as request:
            result = worker.run()
        request.assert_not_called()
        self.assertFalse(result["passed"])
        self.assertTrue(result["pendingReport"])
        self.assertTrue(result["pendingUpload"])
        self.assertEqual(1, result["simulatedOfflineCycles"])
        state = self.persisted(worker)
        self.assertEqual(0, state["outstandingReport"]["seq"])
        self.assertEqual("CREATE", state["outstandingUpload"]["stage"])
        self.assert_private_state(config)

    def test_provider_error_text_and_request_credentials_never_enter_state_or_failure(self):
        for failure_kind in ("unauthorized", "unavailable", "connection"):
            with self.subTest(failure=failure_kind):
                config, _, _ = self.configuration(name="failure-" + failure_kind)
                worker = self.worker(config)
                token = config["devices"][0]["deviceToken"]
                if failure_kind == "connection":
                    failure = urllib.error.URLError("Synthetic private provider detail " + token)
                else:
                    failure = urllib.error.HTTPError("http://127.0.0.1/private/" + token,
                                                     401 if failure_kind == "unauthorized" else 503,
                                                     "Synthetic private provider detail " + token,
                                                     {"Private-Diagnostic": token}, io.BytesIO(token.encode()))
                output = io.StringIO()
                worker.opener = mock.Mock()
                worker.opener.open.side_effect = failure
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                    result = worker.run()
                self.assertFalse(result["passed"])
                self.assertNotIn(token, json.dumps(result))
                if failure_kind == "unauthorized":
                    self.assertIn("HTTP 401", result["failure"])
                else:
                    self.assertEqual(1, result["transientFailures"])
                    self.assertTrue(result["pendingReport"])
                request = worker.opener.open.call_args.args[0]
                self.assertEqual(token, request.get_header("X-device-token"))
                self.assertNotIn(token, request.full_url)
                self.assert_private_state(config, output.getvalue())


if __name__ == "__main__":
    unittest.main()
