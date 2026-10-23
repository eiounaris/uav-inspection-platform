#!/usr/bin/env python3
"""Run or recheck the retained M2 demo through a packaged backend's public HTTP APIs."""

import argparse
import base64
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from backend_env import ROOT, backend_environment, fabric_environment, java21

WORK = ROOT / ".local/mvp-demo"
INSPECTOR = "com.uav.inspection.blockchain.MvpEvidenceInspector"
JAR = ROOT / "backend/target/inspection-backend-0.1.0.jar"
OWNER = "00000000-0000-4000-8000-000000000001"
OPERATOR = "00000000-0000-4000-8000-000000000002"
DRONE = "20000000-0000-4000-8000-000000000001"
UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
PRIVATE_FIELDS = re.compile(r'"(?:objectKey|object_key|transactionBytes|commitBytes|transaction_bytes|commit_bytes)"\s*:')


class DemoError(Exception):
    """Only fixed check names and safe local paths may be included in these messages."""


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def private_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name("." + path.name + "." + uuid.uuid4().hex)
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def local_path(path):
    return str(path.resolve().relative_to(ROOT))


def safe_json(path):
    try:
        value = json.loads(path.read_text())
    except (OSError, ValueError):
        raise DemoError("Cannot read required local JSON: " + local_path(path)) from None
    if not isinstance(value, dict):
        raise DemoError("Expected a JSON object: " + local_path(path))
    return value


def no_other_backends(allowed_pid=None):
    """Inspect actual Java processes only; shell wrappers can contain unrelated command text."""
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit() or int(entry.name) == allowed_pid:
            continue
        try:
            if entry.stat().st_uid != os.getuid():
                continue
            if (entry / "comm").read_text().strip() != "java":
                continue
            arguments = (entry / "cmdline").read_bytes().decode("utf-8", errors="replace").split("\0")
        except (OSError, PermissionError):
            continue
        if any("inspection-backend" in value or value.startswith("com.uav.inspection.")
               or value == "com.uav.inspection.UavInspectionApplication" for value in arguments):
            raise DemoError("Another project backend or Java business probe is running; stop it before the retained demo")


class Demo:
    def __init__(self, environment, java, run, original_run=None):
        self.environment, self.java, self.run = environment, java, run
        self.original_run = original_run
        self.recheck = original_run is not None
        self.secrets = [value for key, value in environment.items()
                        if value and (key.endswith(("PASSWORD", "SECRET", "KEY")) or key == "JWT_SECRET")]
        self.report = {"formatVersion": 1, "mode": "RECHECK" if self.recheck else "CREATE",
                       "runId": run.name, "startedAt": now(), "passed": False,
                       "businessDataRetained": True, "checks": [], "requests": []}
        self.resources = {}
        self.process = None
        self.application_log = run / "application.log"
        self.inspector_log = run / "inspector.log"
        self.base_url = None
        self.sequence = 0
        self.topology = "inspection.mvp." + uuid.uuid4().hex
        self.exchange = self.topology + ".events"
        self.dead_exchange = self.topology + ".dead.events"
        self.queues = [self.topology + ".finalize", self.topology + ".anchor", self.topology + ".dead"]
        self.topology_started = False
        self.report["topology"] = {"exchange": self.exchange, "queues": self.queues}
        self.jar_digest = sha(JAR.read_bytes())
        self.report["packagedJarSha256"] = self.jar_digest
        self.credentials_path = Path(environment["DEMO_CREDENTIALS_PATH"])
        self.original_credentials = sha(self.credentials_path.read_bytes())
        self.original_env = sha((ROOT / ".env").read_bytes()) if (ROOT / ".env").exists() else None
        credentials = safe_json(self.credentials_path)
        passwords = credentials.get("passwords")
        if credentials.get("formatVersion") != 1 or not isinstance(passwords, dict):
            raise DemoError("Existing demo credential format is invalid")
        self.passwords = {}
        for name in ("owner-admin", "operator-admin", "operator-user"):
            value = passwords.get(name)
            if not isinstance(value, str) or not value:
                raise DemoError("A required existing demo account password is missing")
            self.passwords[name] = value
            self.secrets.append(value)
        host = environment.get("RABBITMQ_HOST") or "127.0.0.1"
        if host not in ("127.0.0.1", "localhost", "::1"):
            raise DemoError("M2 demo RabbitMQ management must use localhost")
        host = "[::1]" if host == "::1" else host
        port = environment.get("RABBITMQ_MANAGEMENT_PORT") or "15672"
        if not port.isdecimal() or not 1 <= int(port) <= 65535:
            raise DemoError("Invalid RabbitMQ management port")
        self.rabbit_url = f"http://{host}:{port}"
        user = environment.get("RABBITMQ_USERNAME") or "uav_app"
        password = environment.get("RABBITMQ_PASSWORD")
        if not password:
            raise DemoError("RABBITMQ_PASSWORD is required")
        self.rabbit_auth = "Basic " + base64.b64encode((user + ":" + password).encode()).decode()
        self.vhost = urllib.parse.quote(environment.get("RABBITMQ_VIRTUAL_HOST") or "/", safe="")
        classpath_file = ROOT / "backend/target/database-probe.classpath"
        compiled = ROOT / ("backend/target/test-classes/" + INSPECTOR.replace(".", "/") + ".class")
        if not classpath_file.is_file() or not compiled.is_file():
            raise DemoError("Build the backend with -P database-probe,fabric-probe clean verify first")
        dependencies = classpath_file.read_text().strip()
        if not dependencies:
            raise DemoError("The database-probe classpath is empty")
        self.classpath = os.pathsep.join([str(ROOT / "backend/target/test-classes"),
                                         str(ROOT / "backend/target/classes"), dependencies])

    def check(self, condition, name):
        self.report["checks"].append({"check": name, "passed": bool(condition)})
        if not condition:
            raise DemoError(name)

    def safe_write(self, path, value):
        text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
        if (PRIVATE_FIELDS.search(text) or "upload-parts/" in text or "evidence-files/" in text
                or any(secret in text for secret in self.secrets)):
            raise DemoError("Refusing to persist credentials or private provider data")
        private_bytes(path, text.encode())

    def checkpoint(self):
        self.safe_write(self.run / "resources.json", self.resources)
        self.report["caseCount"] = len(self.report["requests"])
        self.report["checkCount"] = len(self.report["checks"])
        self.safe_write(self.run / "report.json", self.report)

    def rabbit(self, method, path, allowed=(200,), body=None):
        headers = {"Authorization": self.rabbit_auth, "Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.rabbit_url + path, data=data, headers=headers, method=method)
        try:
            with HTTP.open(request, timeout=10) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as failure:
            status, raw = failure.code, failure.read()
            failure.close()
        except (OSError, urllib.error.URLError):
            raise DemoError("RabbitMQ management request failed") from None
        if status not in allowed:
            raise DemoError("RabbitMQ management returned unexpected HTTP status " + str(status))
        try:
            return json.loads(raw) if raw else None
        except ValueError:
            raise DemoError("RabbitMQ management response was not JSON") from None

    def check_consumers(self, own=False):
        consumers = self.rabbit("GET", "/api/consumers/" + self.vhost)
        if not isinstance(consumers, list):
            raise DemoError("RabbitMQ consumer listing was invalid")
        allowed = set(self.queues) if own else set()
        if any(item.get("queue", {}).get("name") not in allowed for item in consumers):
            raise DemoError("Other RabbitMQ consumers are active; stop shared backend consumers before this demo")
        self.check(True, "no_other_rabbitmq_consumers")
        return consumers

    def inspect(self, phase, output, input_path=None, attempts=1):
        command = [self.java, "-Djava.awt.headless=true", "-cp", self.classpath, INSPECTOR, phase]
        if input_path is not None:
            command.append(str(input_path))
        command.append(str(output))
        last = None
        for number in range(attempts):
            descriptor = os.open(self.inspector_log, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
            with os.fdopen(descriptor, "ab") as stream:
                result = subprocess.run(command, cwd=ROOT, env=self.environment, stdout=stream,
                                        stderr=subprocess.STDOUT, timeout=90)
            if result.returncode == 0 and output.is_file():
                last = safe_json(output)
                if last.get("passed") is True:
                    self.report[phase + "InspectorAttempts"] = number + 1
                    return last
            if number + 1 < attempts:
                time.sleep(1)
        raise DemoError("Read-only " + phase + " inspector failed; inspect the private inspector log/report")

    def start(self):
        no_other_backends()
        self.check_consumers()
        child = {key: value for key, value in self.environment.items() if not key.startswith("SPRING_")}
        child["TZ"] = "Asia/Shanghai"
        if self.recheck:
            child["SPRING_APPLICATION_JSON"] = json.dumps({"inspection": {
                "messaging": {"publisher-enabled": False, "recovery-enabled": False},
                "ledger": {"recovery-enabled": False}}})
        arguments = [self.java, "-jar", str(JAR), "--spring.profiles.active=local,database,storage,messaging,ledger",
                     "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                     "--logging.level.com.uav.inspection=INFO",
                     "--inspection.messaging.exchange=" + self.exchange,
                     "--inspection.messaging.finalize-queue=" + self.queues[0],
                     "--inspection.messaging.anchor-queue=" + self.queues[1],
                     "--inspection.messaging.dead-letter-exchange=" + self.dead_exchange,
                     "--inspection.messaging.dead-letter-queue=" + self.queues[2]]
        descriptor = os.open(self.application_log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            self.process = subprocess.Popen(arguments, cwd=ROOT, env=child, stdout=stream,
                                            stderr=subprocess.STDOUT, start_new_session=True)
        self.topology_started = True
        self.report["applicationPid"] = self.process.pid
        self.report["backgroundProcessing"] = "DISABLED_FOR_RECHECK" if self.recheck else "NORMAL_AUTOMATIC_DEFAULTS"
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise DemoError("Packaged backend exited during startup; inspect the private application log")
            text = self.application_log.read_text(errors="replace")
            match = re.search(r"Tomcat started on port (\d+)", text)
            if match:
                self.base_url = "http://127.0.0.1:" + match.group(1)
                break
            time.sleep(0.2)
        if self.base_url is None:
            raise DemoError("Packaged backend startup exceeded 90 seconds")
        self.request("packaged_backend_status", "GET", "/api/v1/system/status")
        no_other_backends(self.process.pid)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            consumers = self.check_consumers(own=True)
            names = [item.get("queue", {}).get("name") for item in consumers]
            if len(consumers) == 2 and names.count(self.queues[0]) == names.count(self.queues[1]) == 1:
                self.check(all(item.get("ack_required") is True and item.get("prefetch_count") == 1 for item in consumers),
                           "both_real_consumers_require_manual_ack_and_prefetch_one")
                self.report["consumerEvidence"] = [{"queue": item["queue"]["name"], "ackRequired": item["ack_required"],
                                                    "prefetchCount": item["prefetch_count"]} for item in consumers]
                break
            time.sleep(0.5)
        else:
            raise DemoError("Own finalize and anchor consumers did not both become active")
        self.check(True, "packaged_jar_serves_real_http_on_random_loopback_port")

    def request(self, label, method, path, token=None, body=None, raw=None, content_type=None,
                expected=200, snapshot=False):
        self.sequence += 1
        trace = "t014-" + self.run.name[-20:] + "-" + str(self.sequence)
        headers = {"Accept": "application/json", "X-Trace-Id": trace}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with HTTP.open(request, timeout=65) as response:
                status, response_headers, response_data = response.status, response.headers, response.read()
        except urllib.error.HTTPError as failure:
            status, response_headers, response_data = failure.code, failure.headers, failure.read()
            failure.close()
        except (OSError, urllib.error.URLError):
            raise DemoError(label + ": HTTP connection failed; business POSTs are never blindly retried") from None
        self.report["requests"].append({"case": label, "method": method, "path": path,
                                        "status": status, "traceId": trace})
        self.check(status == expected, label + "_http_" + str(expected))
        self.check("application/json" in response_headers.get("Content-Type", ""), label + "_json")
        try:
            envelope = json.loads(response_data)
        except ValueError:
            raise DemoError(label + ": response is not valid JSON") from None
        self.check(isinstance(envelope, dict) and set(envelope) == {"code", "message", "data", "traceId"}
                   and envelope["code"] == "OK", label + "_uniform_success_envelope")
        self.check(envelope["traceId"] == trace and response_headers.get("X-Trace-Id") == trace,
                   label + "_trace_matches")
        text = response_data.decode(errors="replace")
        self.check(not PRIVATE_FIELDS.search(text) and "upload-parts/" not in text and "evidence-files/" not in text
                   and not any(secret in text for secret in self.secrets), label + "_no_private_provider_data")
        if snapshot:
            self.safe_write(self.run / (label + ".json"), envelope)
        return envelope["data"]

    def login(self, username):
        data = self.request("login_" + username, "POST", "/api/v1/auth/login",
                            body={"username": username, "password": self.passwords[username]})
        token = data.get("accessToken")
        self.check(isinstance(token, str) and bool(token), "existing_demo_login_returns_access_token")
        self.secrets.append(token)
        current = self.request("current_" + username, "GET", "/api/v1/users/me", token)
        return token, current

    def copy_request(self, label, version, token, content):
        boundary = "uav-mvp-" + uuid.uuid4().hex
        prefix = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="inspection-copy.png"'
                  + "\r\nContent-Type: image/png\r\n\r\n").encode()
        body = prefix + content + ("\r\n--" + boundary + "--\r\n").encode()
        return self.request(label, "POST", "/api/v1/evidence-versions/" + version + "/verifications?source=COPY",
                            token, raw=body, content_type="multipart/form-data; boundary=" + boundary, snapshot=True)

    def poll(self, label, path, token, complete, deadline=180):
        limit = time.monotonic() + deadline
        while time.monotonic() < limit:
            value = self.request(label, "GET", path, token)
            if complete(value):
                self.safe_write(self.run / (label + ".json"), {"data": value})
                return value
            if value.get("processingStatus") in ("FAILED", "EXPIRED") or (value.get("status") == "FAILED" and not value.get("retryable")):
                raise DemoError(label + ": business processing entered a non-retryable failure state")
            if self.process.poll() is not None:
                raise DemoError("Packaged backend exited while processing the retained demo")
            time.sleep(0.5)
        raise DemoError(label + ": asynchronous processing exceeded the bounded polling window")

    def samples(self):
        origin = self.original_run if self.recheck else ROOT / "examples/files"
        path = origin / ("copy-original.png" if self.recheck else "inspection-sample.png")
        original = path.read_bytes()
        self.check(len(original) == 79 and original.startswith(b"\x89PNG\r\n\x1a\n"), "real_bundled_single_part_png_is_79_bytes")
        modified = bytearray(original)
        modified[-9] ^= 1
        modified = bytes(modified)
        private_bytes(self.run / "copy-original.png", original)
        private_bytes(self.run / "copy-modified.png", modified)
        self.report["copies"] = {"original": local_path(self.run / "copy-original.png"),
                                 "modified": local_path(self.run / "copy-modified.png")}
        return original, modified

    def create(self, owner, admin, uploader, admin_user, uploader_user, original, modified):
        root = "/api/v1"
        self.resources.update(formatVersion=1, expectedSha256=sha(original), changedSha256=sha(modified), size=len(original))
        task = self.request("create_task", "POST", root + "/tasks", owner, body={
            "name": "T014-MVP-" + self.run.name, "scope": "保留的 M2 单文件真实闭环演示",
            "requirements": [{"name": "巡检现场照片", "required": True}]}, snapshot=True)
        self.resources["taskId"] = task["id"]
        self.resources["requirementId"] = task["requirements"][0]["id"]
        self.resources["evidenceId"] = task["requirements"][0]["evidenceId"]
        self.checkpoint()
        task_path = root + "/tasks/" + task["id"]
        task = self.request("assign_task", "POST", task_path + "/assign", owner, body={
            "operatorOrgId": OPERATOR, "operatorAdminId": admin_user["id"], "expectedLockVersion": task["lockVersion"]})
        task = self.request("add_uploader_membership", "PUT", task_path + "/members", admin, body={
            "userIds": [admin_user["id"], uploader_user["id"]], "expectedLockVersion": task["lockVersion"]})
        task = self.request("create_subtask", "POST", task_path + "/subtasks", admin, body={
            "name": "MVP-单机巡检", "requirementIds": [self.resources["requirementId"]], "expectedLockVersion": task["lockVersion"]})
        subtask = task["subtasks"][0]
        self.resources["subtaskId"] = subtask["id"]
        self.checkpoint()
        execution = self.request("create_initial_execution", "POST", root + "/subtasks/" + subtask["id"] + "/executions", admin,
                                 body={"droneId": DRONE, "expectedLockVersion": subtask["lockVersion"]})
        self.resources["executionId"] = execution["id"]
        task = self.request("refresh_task_before_start", "GET", task_path, admin)
        task = self.request("start_task", "POST", task_path + "/start", admin,
                            body={"expectedLockVersion": task["lockVersion"]}, snapshot=True)
        self.check(task["status"] == "RUNNING", "formal_task_is_started_and_keeps_running_state")
        self.resources["clientRequestId"] = str(uuid.uuid4())
        self.checkpoint()
        upload = self.request("create_upload_session", "POST", root + "/upload-sessions", uploader, body={
            "clientRequestId": self.resources["clientRequestId"], "requirementId": self.resources["requirementId"],
            "executionId": execution["id"], "filename": "inspection-sample.png", "contentType": "image/png", "size": len(original)})
        self.resources["uploadId"] = upload["uploadId"]
        self.check(upload["partCount"] == 1, "formal_upload_has_one_business_part")
        self.checkpoint()
        upload_path = root + "/upload-sessions/" + upload["uploadId"]
        part = self.request("upload_single_raw_part", "PUT", upload_path + "/parts/1", uploader,
                            raw=original, content_type="application/octet-stream", snapshot=True)
        self.check(part["sha256"] == sha(original) and part["size"] == len(original), "server_part_digest_matches_real_png_bytes")
        accepted = self.request("complete_upload_accepted", "POST", upload_path + "/complete", uploader, expected=202, snapshot=True)
        self.check(accepted["processingStatus"] in ("VERIFYING", "COMPLETED"), "http_202_accepts_processing_without_claiming_ledger_confirmation")
        ready = self.poll("completed_upload", upload_path, uploader,
                          lambda value: value.get("processingStatus") == "COMPLETED" and value.get("versionId"))
        self.resources["versionId"] = ready["versionId"]
        self.checkpoint()
        version_path = root + "/evidence-versions/" + ready["versionId"]
        anchor = self.poll("confirmed_anchor", version_path + "/anchor", owner,
                           lambda value: value.get("status") == "CONFIRMED")
        self.resources["submissionId"] = anchor["submissionId"]
        self.resources["confirmedTxId"] = anchor["confirmedTxId"]
        self.check(re.fullmatch(r"[0-9a-f]{64}", anchor["confirmedTxId"]) is not None, "confirmed_anchor_has_actual_first_transaction_id")
        self.checkpoint()

    def verify_existing(self, owner, uploader, original, modified):
        data = self.resources
        version_path = "/api/v1/evidence-versions/" + data["versionId"]
        version = self.request("retained_version_metadata", "GET", version_path, owner, snapshot=True)
        self.check(version["sha256"] == data["expectedSha256"] == sha(original) and version["size"] == 79
                   and version["taskId"] == data["taskId"] and version["evidenceId"] == data["evidenceId"],
                   "same_retained_version_matches_source_bytes_and_task_graph")
        anchor = self.request("retained_anchor", "GET", version_path + "/anchor", uploader, snapshot=True)
        self.check(anchor["status"] == "CONFIRMED" and anchor["submissionId"] == data["submissionId"]
                   and anchor["confirmedTxId"] == data["confirmedTxId"], "same_original_anchor_and_transaction_are_reused")
        stored = self.request("stored_match", "POST", version_path + "/verifications?source=STORED", owner, snapshot=True)
        identical = self.copy_request("copy_match", data["versionId"], uploader, original)
        changed = self.copy_request("copy_mismatch", data["versionId"], uploader, modified)
        for view, expected_result, actual_sha in ((stored, "MATCH", sha(original)), (identical, "MATCH", sha(original)),
                                                  (changed, "MISMATCH", sha(modified))):
            self.check(view["result"] == expected_result and view["actualSha256"] == actual_sha
                       and view["ledgerSha256"] == sha(original) and view["ledgerTxId"] == data["confirmedTxId"],
                       "real_verification_" + expected_result.lower() + "_compares_full_bytes_with_original_ledger_record")
        self.check(stored["source"] == "STORED" and identical["source"] == changed["source"] == "COPY",
                   "stored_and_request_local_copy_sources_are_distinct")
        data["verificationIds"] = {"storedMatch": stored["id"], "copyMatch": identical["id"], "copyMismatch": changed["id"]}
        history = self.request("verification_history", "GET", version_path + "/verifications?page=1&pageSize=100", owner, snapshot=True)
        ids = {item["id"] for item in history["items"]}
        self.check(set(data["verificationIds"].values()).issubset(ids), "same_version_history_contains_all_three_real_verifications")
        self.report["verificationResults"] = [{key: view.get(key) for key in ("id", "source", "result", "actualSha256", "ledgerSha256", "ledgerTxId")}
                                               for view in (stored, identical, changed)]
        self.checkpoint()

    def wait_empty_queues(self):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            self.check_consumers(own=True)
            empty = True
            for queue in self.queues:
                info = self.rabbit("GET", "/api/queues/" + self.vhost + "/" + urllib.parse.quote(queue, safe=""))
                empty = empty and info.get("messages", 0) == 0 and info.get("messages_unacknowledged", 0) == 0
            if empty:
                self.check(True, "own_automatic_message_queues_are_drained")
                return
            time.sleep(0.5)
        raise DemoError("Own demo queues did not drain; business data and topology are retained")

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        self.report["ownApplicationStopped"] = self.process is None or self.process.poll() is not None

    def cleanup_empty_topology(self):
        if not self.topology_started:
            return True
        # Rabbit management statistics can briefly retain a stopped consumer. Wait
        # for that view to settle, then retain broker-side empty/unused guards.
        deadline = time.monotonic() + 20
        while True:
            busy = False
            for queue in self.queues:
                path = "/api/queues/" + self.vhost + "/" + urllib.parse.quote(queue, safe="")
                info = self.rabbit("GET", path, allowed=(200, 404))
                if info is not None and (info.get("messages", 0) or info.get("messages_unacknowledged", 0) or info.get("consumers", 0)):
                    busy = True
            if not busy:
                break
            if time.monotonic() >= deadline:
                self.report["emptyOwnTopologyDeleted"] = False
                return False
            time.sleep(0.5)
        for queue in self.queues:
            self.rabbit("DELETE", "/api/queues/" + self.vhost + "/" + urllib.parse.quote(queue, safe="")
                        + "?if-empty=true&if-unused=true", allowed=(204, 404))
        for exchange in (self.exchange, self.dead_exchange):
            self.rabbit("DELETE", "/api/exchanges/" + self.vhost + "/" + urllib.parse.quote(exchange, safe="")
                        + "?if-unused=true", allowed=(204, 404))
        self.report["emptyOwnTopologyDeleted"] = True
        return True

    def execute(self):
        self.report["stage"] = "preflight"
        no_other_backends()
        self.check_consumers()
        fresh_preflight = self.run / "preflight.json"
        self.inspect("preflight", fresh_preflight)
        original, modified = self.samples()
        if self.recheck:
            origin_report = safe_json(self.original_run / "report.json")
            self.check(origin_report.get("passed") is True, "recheck_uses_a_previously_successful_retained_run")
            self.resources = safe_json(self.original_run / "resources.json")
            for field in ("taskId", "requirementId", "subtaskId", "executionId", "uploadId", "versionId", "evidenceId", "submissionId"):
                self.check(isinstance(self.resources.get(field), str) and UUID.fullmatch(self.resources[field]) is not None,
                           "retained_" + field + "_is_valid")
            self.check(self.resources.get("expectedSha256") == sha(original) and self.resources.get("changedSha256") == sha(modified),
                       "retained_copy_bytes_match_original_demo_hashes")
            self.report["sourceRunDirectory"] = local_path(self.original_run)
        try:
            self.report["stage"] = "packaged_http"
            self.start()
            owner, owner_user = self.login("owner-admin")
            admin, admin_user = self.login("operator-admin")
            uploader, uploader_user = self.login("operator-user")
            self.check(owner_user["orgId"] == OWNER and admin_user["orgId"] == uploader_user["orgId"] == OPERATOR,
                       "existing_demo_users_have_expected_institution_scope")
            if not self.recheck:
                self.create(owner, admin, uploader, admin_user, uploader_user, original, modified)
            else:
                self.request("same_retained_task", "GET", "/api/v1/tasks/" + self.resources["taskId"], owner, snapshot=True)
                self.request("same_retained_upload", "GET", "/api/v1/upload-sessions/" + self.resources["uploadId"], uploader, snapshot=True)
            self.verify_existing(owner, uploader, original, modified)
            self.wait_empty_queues()
            self.report["stage"] = "readonly_inspection"
            inspection_input = {key: self.resources[key] for key in ("formatVersion", "taskId", "requirementId", "subtaskId", "executionId", "uploadId", "versionId", "evidenceId", "submissionId", "expectedSha256", "changedSha256", "size", "verificationIds")}
            inspection_input["preflightReport"] = str((self.original_run if self.recheck else self.run) / "preflight.json")
            if self.recheck:
                inspection_input["recheckPreflightReport"] = str(fresh_preflight)
            input_path = self.run / "inspector-input.json"
            self.safe_write(input_path, inspection_input)
            inspection = self.inspect("inspect", self.run / "inspection.json", input_path, attempts=5)
            self.check(inspection.get("passed") is True, "readonly_mysql_object_and_two_institution_ledger_inspection_passes")
            self.report["retainedResources"] = {key: self.resources[key] for key in ("taskId", "uploadId", "versionId", "evidenceId", "confirmedTxId", "verificationIds")}
            self.report["stage"] = "complete"
            self.report["completedAt"] = now()
            self.report["passed"] = True
        finally:
            self.stop()
            cleaned = self.cleanup_empty_topology()
            if self.report.get("passed"):
                self.check(cleaned, "only_own_empty_message_topology_is_deleted_after_shutdown")
            self.check(sha(self.credentials_path.read_bytes()) == self.original_credentials, "original_demo_credentials_unchanged")
            self.check((sha((ROOT / ".env").read_bytes()) if (ROOT / ".env").exists() else None) == self.original_env,
                       "original_env_file_unchanged")
            self.checkpoint()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recheck", type=Path, help="Re-read and verify a successful .local/mvp-demo/<runId>; no new task/version/transaction")
    arguments = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(WORK / ".run.lock", os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, "r+") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise DemoError("Another retained M2 demo owns the local run lock") from None
        environment = fabric_environment(backend_environment(), local=True)
        java = java21(environment)
        if not JAR.is_file():
            raise DemoError("Build the packaged backend JAR before running this demo")
        name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
        origin = None
        if arguments.recheck:
            origin = arguments.recheck.resolve()
            if not origin.is_relative_to(WORK.resolve()) or not (origin / "resources.json").is_file():
                raise DemoError("--recheck requires a successful local MVP demo directory")
        run = (origin / "rechecks" / name) if origin else (WORK / name)
        run.mkdir(parents=True, mode=0o700)
        print("Run directory: " + local_path(run), flush=True)
        demo = Demo(environment, java, run, origin)
        try:
            demo.execute()
        except Exception as failure:
            demo.report["passed"] = False
            demo.report["failureType"] = type(failure).__name__
            if isinstance(failure, DemoError):
                demo.report["failedCheck"] = str(failure)
            demo.checkpoint()
            raise
        latest = {"formatVersion": 1, "verifiedAt": now(), "mode": demo.report["mode"],
                  "runDirectory": local_path(run), "report": local_path(run / "report.json")}
        demo.safe_write((origin / "latest-recheck.json") if origin else (WORK / "latest.json"), latest)
        print("[PASS] M2 retained HTTP demo: " + local_path(run / "report.json"))


if __name__ == "__main__":
    try:
        main()
    except DemoError as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        print("[FAIL] M2 demo failed (" + type(error).__name__ + "); inspect private local reports/logs", file=sys.stderr)
        sys.exit(1)
