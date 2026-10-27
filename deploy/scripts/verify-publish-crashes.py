#!/usr/bin/env python3
"""Verify T027 real publishing/candidate process halts and automatic recovery in new JVMs."""
import base64
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

from backend_env import ROOT, backend_environment, fabric_environment, java21

WORK = ROOT / ".local/publish-crash-probe"
PROBE = "com.uav.inspection.messaging.probe.PublishCrashRecoveryProbe"


def no_other_backends():
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            if entry.stat().st_uid != os.getuid() or (entry / "comm").read_text().strip() != "java":
                continue
            arguments = (entry / "cmdline").read_bytes().decode("utf-8", errors="replace").split("\0")
        except OSError:
            continue
        if any("inspection-backend" in value or value.startswith("com.uav.inspection.") for value in arguments):
            raise RuntimeError("Stop other project backend or business probes before running the automatic messaging workers")


def no_other_consumers(environment):
    host = environment.get("RABBITMQ_HOST") or "127.0.0.1"
    if host not in ("127.0.0.1", "localhost", "::1"):
        raise RuntimeError("Publish crash probe RabbitMQ management must use localhost")
    port = environment.get("RABBITMQ_MANAGEMENT_PORT") or "15672"
    if not port.isdecimal() or not 1 <= int(port) <= 65535:
        raise RuntimeError("Invalid RabbitMQ management port")
    password = environment.get("RABBITMQ_PASSWORD")
    if not password:
        raise RuntimeError("RABBITMQ_PASSWORD is required")
    host = "[::1]" if host == "::1" else host
    vhost = urllib.parse.quote(environment.get("RABBITMQ_VIRTUAL_HOST") or "/", safe="")
    authentication = base64.b64encode(((environment.get("RABBITMQ_USERNAME") or "uav_app") + ":" + password).encode()).decode()
    request = urllib.request.Request(f"http://{host}:{port}/api/consumers/{vhost}", headers={"Authorization": "Basic " + authentication})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=10) as response:
            consumers = json.load(response)
    except (OSError, ValueError):
        raise RuntimeError("Cannot verify that RabbitMQ has no shared consumers") from None
    if not isinstance(consumers, list) or consumers:
        raise RuntimeError("Stop shared RabbitMQ consumers before running the automatic messaging workers")


def private_json(path, data):
    temporary = path.parent / (".metadata-" + uuid.uuid4().hex + ".tmp")
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump(data, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    environment = {key: value for key, value in backend_environment().items() if not key.startswith("SPRING_")}
    environment = fabric_environment(environment, local=True)
    environment["TZ"] = "Asia/Shanghai"
    java = java21(environment)
    classpath_file = ROOT / "backend/target/database-probe.classpath"
    compiled = ROOT / ("backend/target/test-classes/" + PROBE.replace(".", "/") + ".class")
    if not classpath_file.is_file() or not compiled.is_file():
        raise RuntimeError("Build backend/pom.xml with -P database-probe clean verify first")
    dependencies = classpath_file.read_text().strip()
    if not dependencies:
        raise RuntimeError("The database-probe dependency classpath is empty")
    credentials, env_file = Path(environment["DEMO_CREDENTIALS_PATH"]), ROOT / ".env"
    original_credentials = hashlib.sha256(credentials.read_bytes()).digest()
    original_env = hashlib.sha256(env_file.read_bytes()).digest()
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(WORK, 0o700)
    descriptor = os.open(WORK / ".run.lock", os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another publish crash probe owns the run lock") from None
        no_other_backends()
        no_other_consumers(environment)
        name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
        run = WORK / name
        run.mkdir(mode=0o700)
        report, log = run / "report.json", run / "probe.log"
        classpath = os.pathsep.join([str(ROOT / "backend/target/test-classes"), str(ROOT / "backend/target/classes"), dependencies])
        command = [java, "-Djava.awt.headless=true", "-Duser.timezone=Asia/Shanghai", "-cp", classpath, PROBE, "coordinator", str(run)]
        print("Run directory: " + str(run.relative_to(ROOT)), flush=True)
        descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            result = subprocess.run(command, env=environment, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=600)
        if hashlib.sha256(credentials.read_bytes()).digest() != original_credentials or hashlib.sha256(env_file.read_bytes()).digest() != original_env:
            raise RuntimeError("Original demo credentials or .env changed during publish crash verification")
        if result.returncode != 0:
            raise RuntimeError("Publish crash flow failed; inspect " + str(report.relative_to(ROOT)))
        data = json.loads(report.read_text())
        if data.get("passed") is not True:
            raise RuntimeError("Publish crash probe did not report success")
        required = ("originalBusinessDataUnchanged", "ownedRabbitTopologyDeleted", "fabricRecordsRetainedAppendOnly",
                    "beforePublishCrashVerified", "afterConfirmCrashVerified", "afterCandidatePutCrashVerified",
                    "naturalLeaseExpiryVerified", "defaultAutomaticRecoveryVerified", "duplicateDeliveryAfterDoneAcknowledged",
                    "originalEventIdsPreserved", "uniqueBusinessResultsVerified", "distinctChildJvmProcesses",
                    "orphanCandidatePreservedBeforeOwnedTeardown")
        if any(data.get(key) is not True for key in required):
            raise RuntimeError("Publish crash recovery did not prove every required crash, duplicate and preservation boundary")
        if data.get("confirmedVersions") != 3 or len(data.get("ledgerRecordsRetained", [])) != 3:
            raise RuntimeError("Publish crash recovery did not retain three actual Fabric confirmations")
        phases = {"before-publish": 73, "after-confirm": 74, "file-candidate": 75, "recover": 0}
        child_pids = set()
        for phase, code in phases.items():
            phase_report = json.loads((run / (phase + ".json")).read_text())
            if phase_report.get("passed") is not True:
                raise RuntimeError("A publishing crash child did not prove its boundary")
            if code and phase_report.get("intentionalHaltExitCode") != code:
                raise RuntimeError("A publishing crash child did not record the expected actual halt")
            child_pids.add(phase_report["pid"])
        if len(child_pids) != 4 or data.get("coordinatorPid") in child_pids:
            raise RuntimeError("Publishing crashes and recovery must use independent child JVMs")
        summary = {"formatVersion": 1, "passed": True, "verifiedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                   "runDirectory": str(run.relative_to(ROOT)), "report": str(report.relative_to(ROOT)),
                   "javaHttpRequests": data.get("caseCount"), "javaCheckCount": data.get("checkCount"),
                   "confirmedVersions": 3, "realLedgerRegistrations": 3,
                   "originalBusinessDataUnchanged": True, "ownedRabbitTopologyDeleted": True,
                   "credentialFileUnchanged": True, "envFileUnchanged": True,
                   "fixtureCredentialsRetainedOnlyInMemory": True,
                   "distinctChildJvmProcesses": True, "productionLeaseSeconds": 120,
                   "beforePublishCrashVerified": True, "afterConfirmCrashVerified": True,
                   "afterCandidatePutCrashVerified": True, "naturalLeaseExpiryVerified": True,
                   "defaultAutomaticRecoveryVerified": True, "duplicateDeliveryAfterDoneAcknowledged": True,
                   "originalEventIdsPreserved": True, "uniqueBusinessResultsVerified": True,
                   "orphanCandidatePreservedBeforeOwnedTeardown": True,
                   "explicitPostDoneDuplicatePublishes": data.get("explicitPostDoneDuplicatePublishes"),
                   "crashedLocalTemporaryResidueRetainedForEvidence": data.get("crashedLocalTemporaryResidueRetainedForEvidence"),
                   "classpathSha256": hashlib.sha256(dependencies.encode()).hexdigest()}
        private_json(run / "verify.json", summary)
        private_json(WORK / "latest.json", {"verified_at": summary["verifiedAt"], "run_directory": summary["runDirectory"], "report": str((run / "verify.json").relative_to(ROOT))})
    print("[PASS] T027 publish crash recovery report: " + str((run / "verify.json").relative_to(ROOT)))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
