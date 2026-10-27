#!/usr/bin/env python3
"""Verify default processing lease renewal and autonomous business recovery across two JVMs."""
import argparse
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

WORK = ROOT / ".local/recovery-probe"
PROBE = "com.uav.inspection.messaging.probe.ProcessingRecoveryProbe"


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
        raise RuntimeError("Recovery probe RabbitMQ management must use localhost")
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recover", type=Path, help="Resume an intentionally halted, private run directory")
    arguments = parser.parse_args()
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
        raise RuntimeError("The database-probe classpath is empty")
    credentials, env_file = Path(environment["DEMO_CREDENTIALS_PATH"]), ROOT / ".env"
    original_credentials, original_env = hashlib.sha256(credentials.read_bytes()).digest(), hashlib.sha256(env_file.read_bytes()).digest()
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(WORK, 0o700)
    descriptor = os.open(WORK / ".run.lock", os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another recovery probe owns the local run lock") from None
        no_other_backends()
        no_other_consumers(environment)
        if arguments.recover is None:
            name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
            run = WORK / name
            run.mkdir(mode=0o700)
            phases = ("crash", "recover")
            results = {}
        else:
            run = arguments.recover.resolve(strict=True)
            if run.parent != WORK.resolve() or run.is_symlink() or not run.is_dir():
                raise RuntimeError("Recovery must use one existing direct child of .local/recovery-probe")
            if (run / "report.json").is_file() or not (run / "credentials.json").is_file():
                raise RuntimeError("Recovery requires an intentionally halted run that has not started its recovery phase")
            crash = json.loads((run / "crash.json").read_text())
            if crash.get("passed") is not True or crash.get("durableRecoveryCheckpointsBeforeHalt") is not True:
                raise RuntimeError("Recovery requires a successfully recorded real-confirm crash checkpoint")
            phases = ("recover",)
            results = {"crash": crash}
        classpath = os.pathsep.join([str(ROOT / "backend/target/test-classes"), str(ROOT / "backend/target/classes"), dependencies])
        print("Run directory: " + str(run.relative_to(ROOT)), flush=True)
        print("The first JVM holds a real object stream past 120 seconds; recovery waits for an unedited processing lease. Defaults remain 120/30 seconds.", flush=True)
        for phase in phases:
            no_other_backends()
            no_other_consumers(environment)
            log = run / (phase + ".log")
            command = [java, "-Djava.awt.headless=true", "-Duser.timezone=Asia/Shanghai", "-cp", classpath, PROBE, phase, str(run)]
            descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as output:
                result = subprocess.run(command, env=environment, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=600)
            if hashlib.sha256(credentials.read_bytes()).digest() != original_credentials or hashlib.sha256(env_file.read_bytes()).digest() != original_env:
                raise RuntimeError("Original credentials or .env changed during the recovery probe")
            expected_exit = 73 if phase == "crash" else 0
            if result.returncode != expected_exit:
                raise RuntimeError("Recovery " + phase + " phase did not return its expected exit code; inspect " + str(log.relative_to(ROOT)))
            report = run / ("crash.json" if phase == "crash" else "report.json")
            data = json.loads(report.read_text())
            if not isinstance(data, dict) or data.get("passed") is not True:
                raise RuntimeError("Recovery " + phase + " phase did not report success")
            if phase == "crash" and (data.get("durableRecoveryCheckpointsBeforeHalt") is not True or data.get("intentionalHaltExitCode") != 73):
                raise RuntimeError("The first JVM did not prove its durable business recovery checkpoint boundary")
            results[phase] = data
        final, crash = results["recover"], results["crash"]
        if crash.get("pid") == final.get("pid"):
            raise RuntimeError("Recovery crash and recovery did not use independent JVMs")
        if final.get("originalBusinessDataUnchanged") is not True or final.get("ownedRabbitTopologyDeleted") is not True:
            raise RuntimeError("Recovery probe did not verify original data preservation and isolated cleanup")
        if final.get("confirmedVersions") != 3 or final.get("fabricRecordsRetainedAppendOnly") is not True:
            raise RuntimeError("Recovery probe did not retain its three actual Fabric confirmations")
        if any(final.get(key) is not True for key in ("defaultScheduledRecoveryEnabled", "fileDoneBypassed", "anchorDoneBypassed", "originalTransactionAndCommitBytesPreserved", "naturalProcessingLeaseExpiryUsed", "expiredNonterminalOwnerFencedBeforeRecovery")):
            raise RuntimeError("Recovery probe did not prove its automatic scanner and original transaction boundaries")
        if crash.get("processingLeaseSeconds") != 120 or crash.get("heartbeatIntervalSeconds") != 30 or crash.get("longFileIoPassedOriginalExpiry") is not True or crash.get("renewalFailureStoppedBusinessWrites") is not True:
            raise RuntimeError("Recovery probe did not prove unchanged lease defaults and fail-closed long I/O")
        if (run / "credentials.json").exists():
            raise RuntimeError("Recovery probe did not delete its temporary fixture credential file")
        summary = {"formatVersion": 1, "passed": True, "verifiedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                   "runDirectory": str(run.relative_to(ROOT)), "crashReport": str((run / "crash.json").relative_to(ROOT)),
                   "recoveryReport": str((run / "report.json").relative_to(ROOT)), "distinctJvmProcesses": True,
                   "crashPid": crash["pid"], "recoverPid": final["pid"], "intentionalHaltExitCode": 73,
                   "processingLeaseSeconds": crash["processingLeaseSeconds"], "heartbeatIntervalSeconds": crash["heartbeatIntervalSeconds"],
                   "javaHttpRequests": sum(report.get("caseCount", 0) for report in results.values()),
                   "javaCheckCount": sum(report.get("checkCount", 0) for report in results.values()),
                   "longFileIoHeldMillis": crash["longFileIoHeldMillis"], "naturalLeaseWaitMillis": final["naturalLeaseWaitMillis"],
                   "expiredNonterminalOwnerFencedBeforeRecovery": True,
                   "successfulFileRenewals": crash["successfulFileRenewals"], "successfulAnchorRenewals": crash["successfulAnchorRenewals"],
                   "renewalFailureStoppedBusinessWrites": True, "defaultScheduledRecoveryEnabled": True,
                   "originalTransactionAndCommitBytesPreserved": True, "confirmedVersions": 3,
                   "originalBusinessDataUnchanged": True, "credentialFileUnchanged": True, "envFileUnchanged": True,
                   "temporaryFixtureCredentialsDeleted": True, "ownedRabbitTopologyDeleted": True,
                   "classpathSha256": hashlib.sha256(dependencies.encode()).hexdigest()}
        private_json(run / "verify.json", summary)
        private_json(WORK / "latest.json", {"verified_at": summary["verifiedAt"], "run_directory": summary["runDirectory"],
                                           "report": str((run / "verify.json").relative_to(ROOT))})
    print("[PASS] T023 recovery report: " + str((run / "verify.json").relative_to(ROOT)))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
