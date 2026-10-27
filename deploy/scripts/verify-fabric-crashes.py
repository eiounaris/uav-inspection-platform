#!/usr/bin/env python3
"""Verify real Fabric RPC crash boundaries and default recovery across independent JVMs."""
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

WORK = ROOT / ".local/fabric-crash-probe"
PROBE = "com.uav.inspection.messaging.probe.FabricCrashRecoveryProbe"


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
    options = parser.add_mutually_exclusive_group()
    options.add_argument("--recover", type=Path, help="Resume after all three intentional RPC halts")
    options.add_argument("--cleanup", type=Path, help="Remove only this stopped run's chain-off fixtures; retain chain/audit evidence")
    args = parser.parse_args()
    environment = {key: value for key, value in backend_environment().items() if not key.startswith("SPRING_")}
    environment = fabric_environment(environment, local=True)
    environment["TZ"] = "Asia/Shanghai"
    java = java21(environment)
    classpath_file = ROOT / "backend/target/database-probe.classpath"
    compiled = ROOT / ("backend/target/test-classes/" + PROBE.replace(".", "/") + ".class")
    if not classpath_file.is_file() or not compiled.is_file():
        raise RuntimeError("Build backend/pom.xml with -P database-probe,fabric-probe clean verify first")
    dependencies = classpath_file.read_text().strip()
    if not dependencies:
        raise RuntimeError("Empty probe dependency classpath")
    credentials, env_file = Path(environment["DEMO_CREDENTIALS_PATH"]), ROOT / ".env"
    credential_hash, env_hash = hashlib.sha256(credentials.read_bytes()).digest(), hashlib.sha256(env_file.read_bytes()).digest()
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(WORK, 0o700)
    descriptor = os.open(WORK / ".run.lock", os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another Fabric crash probe owns the run lock") from None
        no_other_backends()
        no_other_consumers(environment)
        selected = args.recover or args.cleanup
        if selected is None:
            name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
            run = WORK / name
            run.mkdir(mode=0o700)
            phases = ("setup", "before-submit", "after-submit", "after-commit", "recover")
        else:
            if selected.is_symlink():
                raise RuntimeError("Run directory must not be a symlink")
            run = selected.resolve(strict=True)
            if run.parent != WORK.resolve() or not run.is_dir():
                raise RuntimeError("Use an existing direct child of .local/fabric-crash-probe")
            checkpoint = json.loads((run / "checkpoint.json").read_text())
            if args.recover and not set(("before-submit", "after-submit", "after-commit")).issubset(checkpoint.get("crashes", {})):
                raise RuntimeError("Recovery requires all three recorded intentional halts; incomplete runs may use --cleanup")
            if args.recover and (run / "report.json").exists() and json.loads((run / "report.json").read_text()).get("passed") is True:
                raise RuntimeError("This run already completed; do not re-run it")
            phases = ("cleanup",) if args.cleanup else ("recover",)
        classpath = os.pathsep.join([str(ROOT / "backend/target/test-classes"), str(ROOT / "backend/target/classes"), dependencies])
        print("Run directory: " + str(run.relative_to(ROOT)), flush=True)
        print("Three RPC boundary JVMs halt with exit 73; recovery waits for original unedited 120-second leases.", flush=True)
        results = {}
        for phase in phases:
            no_other_backends()
            no_other_consumers(environment)
            log = run / (phase + ".log")
            report = run / ("report.json" if phase == "recover" else phase + ".json")
            # Failed recovery attempts remain reviewable; never overwrite their original report/log.
            if log.exists() or report.exists():
                previous = uuid.uuid4().hex[:8]
                for path in (log, report):
                    if path.exists():
                        path.rename(run / ("previous-" + phase + "-" + previous + path.suffix))
            descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            command = [java, "-Djava.awt.headless=true", "-Duser.timezone=Asia/Shanghai", "-cp", classpath, PROBE, phase, str(run)]
            print("Phase: " + phase, flush=True)
            with os.fdopen(descriptor, "w") as output:
                completed = subprocess.run(command, env=environment, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=540)
            if hashlib.sha256(credentials.read_bytes()).digest() != credential_hash or hashlib.sha256(env_file.read_bytes()).digest() != env_hash:
                raise RuntimeError("Original credentials or .env changed during Fabric crash verification")
            expected = 73 if phase in ("before-submit", "after-submit", "after-commit") else 0
            if completed.returncode != expected:
                raise RuntimeError("Fabric " + phase + " did not return expected exit " + str(expected) + "; inspect " + str(report.relative_to(ROOT)) + ". Fixtures remain available for recovery/cleanup.")
            data = json.loads(report.read_text())
            if data.get("passed") is not True:
                raise RuntimeError("Fabric phase did not report success: " + phase)
            if expected == 73 and (data.get("intentionalHaltExitCode") != 73 or not data.get("durableCheckpointBeforeHalt")):
                raise RuntimeError("RPC boundary halt did not record its real durable checkpoint")
            results[phase] = data
        if args.cleanup:
            final = results["cleanup"]
            if final.get("originalBusinessDataUnchanged") is not True or final.get("ownedRabbitTopologyDeleted") is not True:
                raise RuntimeError("Cleanup did not verify preservation")
            print("[PASS] Stopped Fabric run fixtures cleaned; original reports and chain records retained", flush=True)
            return
        for phase in ("setup", "before-submit", "after-submit", "after-commit"):
            if phase not in results:
                results[phase] = json.loads((run / (phase + ".json")).read_text())
        final = results["recover"]
        required = ("defaultScheduledRecoveryEnabled", "naturalProcessingLeaseExpiryUsed", "originalTransactionBytesPreserved",
                    "originalSavedCommitBytesPreserved", "postSubmitRecordReconciliationVerified", "repeatedUnknownPlansVerified",
                    "originalBusinessDataUnchanged", "ownedRabbitTopologyDeleted", "fabricRecordsRetainedAppendOnly")
        if any(final.get(key) is not True for key in required) or final.get("confirmedVersions") != 3:
            raise RuntimeError("Recovery did not prove all required original-transaction boundaries")
        pids = [value["pid"] for value in results.values()]
        if len(pids) != 5 or len(set(pids)) != 5:
            raise RuntimeError("All setup/crash/recovery stages must use distinct JVM processes")
        summary = {"formatVersion": 1, "passed": True, "verifiedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                   "runDirectory": str(run.relative_to(ROOT)), "report": str((run / "report.json").relative_to(ROOT)),
                   "realLedgerRegistrations": 3, "confirmedVersions": 3, "distinctJvmProcesses": True,
                   "phasePids": {phase: value["pid"] for phase, value in results.items()},
                   "phaseExpectedExitCodes": {"setup": 0, "before-submit": 73, "after-submit": 73, "after-commit": 73, "recover": 0},
                   "processingLeaseSeconds": 120, "naturalLeaseWaitMillis": final.get("naturalLeaseWaitMillis"),
                   "javaHttpRequests": sum(value.get("caseCount", 0) for value in results.values()),
                   "javaCheckCount": sum(value.get("checkCount", 0) for value in results.values()),
                   "credentialFileUnchanged": True, "envFileUnchanged": True, "fixtureCredentialsPersisted": False,
                   "classpathSha256": hashlib.sha256(dependencies.encode()).hexdigest()}
        summary.update({key: True for key in required})
        private_json(run / "verify.json", summary)
        private_json(WORK / "latest.json", {"verified_at": summary["verifiedAt"], "run_directory": summary["runDirectory"],
                                           "report": str((run / "verify.json").relative_to(ROOT))})
    print("[PASS] T027 Fabric crash report: " + str((run / "verify.json").relative_to(ROOT)))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
