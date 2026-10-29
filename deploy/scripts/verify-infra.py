#!/usr/bin/env python3
"""Seed/check isolated infrastructure probes without third-party Python packages.

Examples:
  python3 deploy/scripts/verify-infra.py --phase seed
  # Recreate infrastructure separately, retaining its volumes.
  python3 deploy/scripts/verify-infra.py --phase check

The script never restarts services. It never prints credentials, raw subprocess
errors, HTTP response errors, authorization headers, or presigned URL queries.
Only its randomly named probe resources are deleted after a successful check.
"""

import argparse
import base64
import datetime as dt
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

from deployment_config import bind_probe_project, infrastructure_project


ROOT = Path(__file__).resolve().parents[2]
RUN_ID_PATTERN = re.compile(r"[a-f0-9]{32}")
KEY_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
TIMEOUT = 30


class ProbeError(Exception):
    """Messages contain only explicitly safe diagnostic information."""


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())


def read_env(path):
    """Read the project's literal KEY=value format; do not expand shell syntax."""
    values = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        raise ProbeError("Cannot read the project .env file") from None
    for number, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or not KEY_PATTERN.fullmatch(key) or key in values:
            raise ProbeError(f"Invalid or duplicate .env key at line {number}")
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ProbeError(f"Unclosed literal .env value at line {number}")
            value = value[1:-1]
        else:
            value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
        values[key] = value
    return values


def required(config, key):
    value = config.get(key, "")
    if not value:
        raise ProbeError(f"Required .env setting is missing: {key}")
    return value


def local_endpoint(value, label):
    try:
        parsed = urllib.parse.urlsplit(value)
        _ = parsed.port
    except ValueError:
        raise ProbeError(f"Invalid {label} endpoint") from None
    if (parsed.scheme not in ("http", "https")
            or parsed.hostname not in ("127.0.0.1", "localhost", "::1")
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ("", "/")):
        raise ProbeError(f"{label} must be a localhost HTTP(S) endpoint without a query or path")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc.lower(), "", "", ""))


def http_request(url, method="GET", body=None, headers=None, allowed=(200,), label="HTTP",
                 expect_expired=False):
    request = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    expiration_identified = False
    try:
        with HTTP.open(request, timeout=TIMEOUT) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        if expect_expired:
            try:
                document = ET.fromstring(error.read(8192))
                code = document.findtext("Code", "")
                message = document.findtext("Message", "").strip().lower()
                expiration_identified = (
                    code in ("InvalidRequest", "AccessDenied", "RequestExpired", "ExpiredToken")
                    and ("expir" in message or code in ("RequestExpired", "ExpiredToken")
                         or (code == "InvalidRequest" and message == "bad request: date is too old")))
            except (ET.ParseError, OSError):
                pass
        error.close()
        data = b""
    except (OSError, urllib.error.URLError):
        raise ProbeError(f"{label}: connection failed") from None
    if status not in allowed:
        raise ProbeError(f"{label}: unexpected HTTP status {status}")
    if expect_expired and not expiration_identified:
        raise ProbeError(f"{label}: response does not identify an expired request")
    return data


def save_state(path, state):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(state, stream, indent=2, sort_keys=True)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def load_state(path):
    if not path.exists():
        return None
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ProbeError("Cannot read the probe state file") from None
    if (not isinstance(state, dict) or state.get("schema_version") != 1
            or not isinstance(state.get("run_id"), str)
            or not RUN_ID_PATTERN.fullmatch(state["run_id"])):
        raise ProbeError("Invalid probe state file")
    return state


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


class InfrastructureProbe:
    def __init__(self, config, run_id):
        self.config = config
        try:
            self.project_name = infrastructure_project(config)
        except ValueError as error:
            raise ProbeError(str(error)) from None
        self.run_id = run_id
        self.marker = "uav-infra-probe:" + run_id
        self.mysql_table = "infra_probe_" + run_id
        self.payload = self.marker.encode("ascii")
        self.redis_key = "uav:infra:probe:" + run_id
        self.queue = "uav.infra.probe." + run_id
        self.object_key = "infra-probe/" + run_id + ".txt"
        self.endpoint = local_endpoint(required(config, "S3_ENDPOINT"), "S3")
        self.public_endpoint = local_endpoint(config.get("S3_PUBLIC_ENDPOINT") or self.endpoint, "Public S3")
        self.access_key = required(config, "S3_ACCESS_KEY")
        self.secret_key = required(config, "S3_SECRET_KEY")
        self.region = required(config, "S3_REGION")
        self.bucket = required(config, "S3_BUCKET")
        if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", self.bucket):
            raise ProbeError("S3_BUCKET must be a DNS-style bucket name")
        host = config.get("RABBITMQ_HOST", "127.0.0.1")
        port = config.get("RABBITMQ_MANAGEMENT_PORT", "15672")
        if host == "::1":
            host = "[::1]"
        self.rabbit_endpoint = local_endpoint(f"http://{host}:{port}", "RabbitMQ management")
        user = required(config, "RABBITMQ_USERNAME")
        password = required(config, "RABBITMQ_PASSWORD")
        self.rabbit_auth = "Basic " + base64.b64encode((user + ":" + password).encode()).decode("ascii")
        self.vhost = urllib.parse.quote(config.get("RABBITMQ_VHOST") or "/", safe="")

    def check_host_ports(self):
        for label, host_key, port_key, default_port in (
                ("mysql", "DB_HOST", "DB_PORT", "3306"),
                ("rabbitmq", "RABBITMQ_HOST", "RABBITMQ_PORT", "5672")):
            host = self.config.get(host_key) or "127.0.0.1"
            if host not in ("127.0.0.1", "localhost", "::1"):
                raise ProbeError(f"{label}: host probe is restricted to localhost")
            try:
                port = int(self.config.get(port_key) or default_port)
                if not 1 <= port <= 65535:
                    raise ValueError
            except ValueError:
                raise ProbeError(f"{label}: invalid host port setting") from None
            try:
                with socket.create_connection((host, port), timeout=5) as connection:
                    connection.settimeout(5)
                    if label == "rabbitmq":
                        connection.sendall(b"AMQP\x00\x00\x09\x01")
                    if not connection.recv(16):
                        raise ProbeError(f"{label}: host port closed without a protocol greeting")
            except OSError:
                raise ProbeError(f"{label}: host protocol greeting failed") from None
            print(f"[PASS] {label}: host protocol port responds (not an authenticated client handshake)",
                  flush=True)

    def compose_exec(self, service, script, arguments=(), input_text=None):
        command = ["docker", "compose", "--project-name", self.project_name,
                   "--env-file", str(ROOT / ".env"), "-f", str(ROOT / "deploy/compose.yml"),
                   "exec", "-T", service, "sh", "-c", script, "--", *arguments]
        # Ambient application variables must not override the explicit .env.
        process_env = {key: value for key, value in os.environ.items() if key not in self.config}
        try:
            result = subprocess.run(command, input=input_text, text=True, capture_output=True,
                                    timeout=TIMEOUT, check=False, env=process_env)
        except (OSError, subprocess.TimeoutExpired):
            raise ProbeError(f"{service}: Docker Compose command failed or timed out") from None
        if result.returncode:
            raise ProbeError(f"{service}: container probe exited with code {result.returncode}")
        return result.stdout.rstrip("\r\n")

    def mysql(self, sql):
        script = ('test -n "$MYSQL_USER" && test "$MYSQL_USER" != root '
                  '&& test -n "$MYSQL_PASSWORD" && test -n "$MYSQL_DATABASE" '
                  '&& export MYSQL_PWD="$MYSQL_PASSWORD" '
                  '&& exec mysql --protocol=TCP --host=127.0.0.1 '
                  '--user="$MYSQL_USER" --database="$MYSQL_DATABASE" '
                  '--batch --raw --skip-column-names')
        return self.compose_exec("mysql", script, input_text=sql)

    def seed_mysql(self):
        self.mysql(f"CREATE TABLE IF NOT EXISTS {self.mysql_table} ("
                   "run_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                   "payload VARCHAR(128) CHARACTER SET ascii NOT NULL) ENGINE=InnoDB;\n"
                   f"INSERT INTO {self.mysql_table}(run_id,payload) VALUES('{self.run_id}','{self.marker}') "
                   f"ON DUPLICATE KEY UPDATE payload='{self.marker}';\n")
        self.check_mysql()

    def check_mysql(self):
        value = self.mysql(f"SELECT payload FROM {self.mysql_table} WHERE run_id='{self.run_id}';\n")
        if value != self.marker:
            raise ProbeError("mysql: persisted probe row is missing or differs")

    def clean_mysql(self):
        self.mysql(f"DROP TABLE IF EXISTS {self.mysql_table};\n")

    def redis(self, *arguments):
        return self.compose_exec("redis", 'test -n "$REDIS_PASSWORD" '
                                 '&& export REDISCLI_AUTH="$REDIS_PASSWORD" '
                                 '&& exec redis-cli --raw "$@"', arguments)

    def seed_redis(self):
        if self.redis("SET", self.redis_key, self.marker) != "OK":
            raise ProbeError("redis: probe SET failed")
        self.check_redis()

    def check_redis(self):
        if self.redis("GET", self.redis_key) != self.marker:
            raise ProbeError("redis: persisted probe key is missing or differs")

    def clean_redis(self):
        if self.redis("DEL", self.redis_key) not in ("0", "1"):
            raise ProbeError("redis: probe cleanup failed")

    def rabbit(self, path, method="GET", payload=None, allowed=(200,)):
        headers = {"Authorization": self.rabbit_auth, "Content-Type": "application/json"}
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        body = http_request(self.rabbit_endpoint + "/api/" + path, method, data, headers,
                            allowed, "rabbitmq " + method)
        if not body:
            return None
        try:
            return json.loads(body)
        except ValueError:
            raise ProbeError("rabbitmq: invalid management response") from None

    def seed_rabbitmq(self):
        queue_path = f"queues/{self.vhost}/{self.queue}"
        self.rabbit(queue_path, "PUT", {"durable": True, "auto_delete": False, "arguments": {}},
                    (201, 204))
        result = self.rabbit(f"exchanges/{self.vhost}/amq.default/publish", "POST", {
            "properties": {"delivery_mode": 2, "content_type": "text/plain"},
            "routing_key": self.queue,
            "payload": base64.b64encode(self.payload).decode("ascii"),
            "payload_encoding": "base64",
        })
        if not isinstance(result, dict) or result.get("routed") is not True:
            raise ProbeError("rabbitmq: persistent probe message was not routed")

    def check_rabbitmq(self):
        queue_path = f"queues/{self.vhost}/{self.queue}"
        queue = self.rabbit(queue_path)
        if not isinstance(queue, dict) or queue.get("durable") is not True:
            raise ProbeError("rabbitmq: durable probe queue is missing")
        messages = self.rabbit(queue_path + "/get", "POST", {
            "count": 1, "ackmode": "ack_requeue_true", "encoding": "base64", "truncate": 1024,
        })
        if not isinstance(messages, list) or len(messages) != 1:
            raise ProbeError("rabbitmq: persisted probe message is missing")
        message = messages[0]
        try:
            payload = base64.b64decode(message["payload"], validate=True)
        except (KeyError, ValueError):
            raise ProbeError("rabbitmq: invalid persisted message payload") from None
        if payload != self.payload or message.get("properties", {}).get("delivery_mode") != 2:
            raise ProbeError("rabbitmq: persisted message content or delivery mode differs")

    def clean_rabbitmq(self):
        self.rabbit(f"queues/{self.vhost}/{self.queue}", "DELETE", allowed=(204, 404))

    def signing_key(self, date):
        def sign(key, value):
            return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()
        key = sign(("AWS4" + self.secret_key).encode("utf-8"), date)
        return sign(sign(sign(key, self.region), "s3"), "aws4_request")

    def object_path(self):
        return "/" + urllib.parse.quote(self.bucket, safe="") + "/" + urllib.parse.quote(self.object_key, safe="/-_.~")

    def signature(self, canonical_request, date_time, scope):
        date = date_time[:8]
        string_to_sign = "\n".join(("AWS4-HMAC-SHA256", date_time, scope,
                                     hashlib.sha256(canonical_request.encode()).hexdigest()))
        return hmac.new(self.signing_key(date), string_to_sign.encode(), hashlib.sha256).hexdigest()

    def s3(self, method, payload=None):
        path = self.object_path()
        host = urllib.parse.urlsplit(self.endpoint).netloc
        date_time = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        scope = date_time[:8] + "/" + self.region + "/s3/aws4_request"
        payload_hash = hashlib.sha256(payload or b"").hexdigest()
        signed_headers = "host;x-amz-content-sha256;x-amz-date"
        canonical_headers = (f"host:{host}\nx-amz-content-sha256:{payload_hash}\n"
                             f"x-amz-date:{date_time}\n")
        canonical = "\n".join((method, path, "", canonical_headers, signed_headers, payload_hash))
        authorization = ("AWS4-HMAC-SHA256 Credential=" + self.access_key + "/" + scope
                         + ", SignedHeaders=" + signed_headers + ", Signature="
                         + self.signature(canonical, date_time, scope))
        return http_request(self.endpoint + path, method, payload, {
            "Host": host, "X-Amz-Date": date_time, "X-Amz-Content-Sha256": payload_hash,
            "Authorization": authorization, "Content-Type": "application/octet-stream",
        }, (200, 204), "s3 " + method)

    def presigned_get(self, expires=2):
        moment = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
        date_time = moment.strftime("%Y%m%dT%H%M%SZ")
        scope = date_time[:8] + "/" + self.region + "/s3/aws4_request"
        host = urllib.parse.urlsplit(self.public_endpoint).netloc
        fields = {"X-Amz-Algorithm": "AWS4-HMAC-SHA256", "X-Amz-Credential": self.access_key + "/" + scope,
                  "X-Amz-Date": date_time, "X-Amz-Expires": str(expires), "X-Amz-SignedHeaders": "host"}
        query = "&".join(urllib.parse.quote(key, safe="-_.~") + "="
                         + urllib.parse.quote(value, safe="-_.~") for key, value in sorted(fields.items()))
        canonical = "\n".join(("GET", self.object_path(), query, f"host:{host}\n", "host", "UNSIGNED-PAYLOAD"))
        signature = self.signature(canonical, date_time, scope)
        url = self.public_endpoint + self.object_path() + "?" + query + "&X-Amz-Signature=" + signature
        return url, moment.timestamp() + expires

    def seed_s3(self):
        self.s3("PUT", self.payload)
        if self.s3("GET") != self.payload:
            raise ProbeError("s3: initial authenticated object read differs")

    def check_s3(self):
        if self.s3("GET") != self.payload:
            raise ProbeError("s3: persisted object is missing or differs")
        http_request(self.public_endpoint + self.object_path(), allowed=(401, 403), label="s3 anonymous GET")
        url, expires_at = self.presigned_get()
        if http_request(url, label="s3 presigned GET") != self.payload:
            raise ProbeError("s3: presigned object read differs")
        # Use the public endpoint when signing; never rewrite the signed host.
        time.sleep(max(0, expires_at + 1.1 - time.time()))
        # Garage returns 400/InvalidRequest for expiration; require an expiry
        # message as well so an unrelated 400 cannot count as a passing check.
        http_request(url, allowed=(400, 401, 403), label="s3 expired presigned GET", expect_expired=True)

    def clean_s3(self):
        self.s3("DELETE")


def select_state(arguments, state_file, project_name="uav-inspection"):
    existing = load_state(state_file)
    supplied = arguments.run_id
    if supplied is not None and not RUN_ID_PATTERN.fullmatch(supplied):
        raise ProbeError("--run-id must contain exactly 32 lowercase hexadecimal characters")
    if arguments.phase == "check":
        if existing:
            try:
                bind_probe_project(existing, project_name)
            except ValueError as error:
                raise ProbeError(str(error)) from None
        run_id = supplied or (existing or {}).get("run_id")
        if not run_id:
            raise ProbeError("check requires --run-id or an existing seed state file")
        if existing and existing["run_id"] != run_id and existing.get("status") != "CLEANED":
            raise ProbeError("State file belongs to another active probe; use a different --state-file")
        if existing and existing["run_id"] == run_id:
            if existing.get("status") in ("CLEANED", "RETAINED"):
                raise ProbeError("This probe was already checked; seed a fresh probe for another persistence test")
            return existing
    else:
        if existing and existing.get("status") not in ("CLEANED", "RETAINED"):
            try:
                bind_probe_project(existing, project_name)
            except ValueError as error:
                raise ProbeError(str(error)) from None
            if existing.get("status") not in ("SEEDING", "SEED_FAILED") or (supplied and supplied != existing["run_id"]):
                raise ProbeError("An active probe already exists; check it first or use a different --state-file")
            return existing
        run_id = supplied or uuid.uuid4().hex
    return {"schema_version": 1, "run_id": run_id, "created_at": utc_now(), "project_name": project_name,
            "status": "NEW", "seeded": [], "cleaned": [], "verified": False}


def run(arguments):
    config = read_env(ROOT / ".env")
    try:
        project_name = infrastructure_project(config)
    except ValueError as error:
        raise ProbeError(str(error)) from None
    state_file = arguments.state_file.resolve()
    state = select_state(arguments, state_file, project_name)
    probe = InfrastructureProbe(config, state["run_id"])
    print("run_id=" + probe.run_id, flush=True)
    print("payload_sha256=" + hashlib.sha256(probe.payload).hexdigest(), flush=True)
    services = ("mysql", "redis", "rabbitmq", "s3")
    try:
        if arguments.phase == "seed":
            state["status"] = "SEEDING"
            save_state(state_file, state)
            probe.check_host_ports()
            for service in services:
                getattr(probe, "seed_" + service)()
                if service not in state["seeded"]:
                    state["seeded"].append(service)
                save_state(state_file, state)
                print(f"[PASS] {service}: isolated probe seeded", flush=True)
            state["status"] = "SEEDED"
            save_state(state_file, state)
            print("[PASS] Seed complete; probes remain for a separate persistence check.", flush=True)
            return
        if not state.get("verified"):
            # Requeue the inspected message; retries can still verify the original data.
            for service in ("mysql", "redis", "s3", "rabbitmq"):
                getattr(probe, "check_" + service)()
                print(f"[PASS] {service}: persisted probe verified", flush=True)
            state["verified"] = True
            state["verified_at"] = utc_now()
            state["status"] = "VERIFIED"
            save_state(state_file, state)
        else:
            print("[INFO] Resuming cleanup after a previously recorded successful check.", flush=True)
        if arguments.keep:
            state["status"] = "RETAINED"
            save_state(state_file, state)
            print("[PASS] Probe evidence retained; RabbitMQ message was read and requeued. "
                  "The probe row, key, object and dedicated queue remain.", flush=True)
            return
        for service in services:
            if service not in state["cleaned"]:
                getattr(probe, "clean_" + service)()
                state["cleaned"].append(service)
                save_state(state_file, state)
                print(f"[PASS] {service}: this run's probe cleaned", flush=True)
        state["status"] = "CLEANED"
        save_state(state_file, state)
        print("[PASS] Check complete; only this run's probe data was removed.", flush=True)
    except Exception:
        state["status"] = ("SEED_FAILED" if arguments.phase == "seed" else
                           "CLEANUP_FAILED" if state.get("verified") else "CHECK_FAILED")
        try:
            save_state(state_file, state)
        except OSError:
            pass
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--phase", choices=("seed", "check"), required=True)
    parser.add_argument("--run-id", help="32-character run ID; otherwise use/generate the state file's run ID")
    parser.add_argument("--state-file", type=Path, default=ROOT / ".local/infra-probe.json")
    parser.add_argument("--keep", action="store_true", help="After check, retain probe evidence instead of deleting it")
    arguments = parser.parse_args()
    if arguments.keep and arguments.phase != "check":
        parser.error("--keep is only valid with --phase check")
    try:
        run(arguments)
    except ProbeError as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        return 1
    except Exception as error:
        # Do not render exception text: URLs and SDK/CLI errors may contain secrets.
        print(f"[FAIL] Probe failed ({type(error).__name__}); unsafe details suppressed", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
