#!/usr/bin/env python3
"""Verify ordered member-account locks through the real TaskService and MySQL."""
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

from backend_env import ROOT, backend_environment, java21

WORK = ROOT / ".local/member-lock-probe"
PROBE = "com.uav.inspection.inspection.probe.TaskMemberLockProbe"


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
            raise RuntimeError("Stop other project backend or business probes before running the database lock regression")


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
            raise RuntimeError("Another member lock regression probe owns the run lock") from None
        no_other_backends()
        name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
        run = WORK / name
        run.mkdir(mode=0o700)
        report, log = run / "report.json", run / "probe.log"
        classpath = os.pathsep.join([str(ROOT / "backend/target/test-classes"), str(ROOT / "backend/target/classes"), dependencies])
        command = [java, "-Djava.awt.headless=true", "-Duser.timezone=Asia/Shanghai", "-cp", classpath, PROBE, str(report)]
        print("Run directory: " + str(run.relative_to(ROOT)), flush=True)
        descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            result = subprocess.run(command, env=environment, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=420)
        if hashlib.sha256(credentials.read_bytes()).digest() != original_credentials or hashlib.sha256(env_file.read_bytes()).digest() != original_env:
            raise RuntimeError("Original demo credentials or .env changed during member lock regression verification")
        if result.returncode != 0:
            raise RuntimeError("Member lock regression flow failed; inspect " + str(report.relative_to(ROOT)))
        data = json.loads(report.read_text())
        if data.get("passed") is not True:
            raise RuntimeError("Member lock regression probe did not report success")
        if (data.get("rounds") != 8 or data.get("successfulMemberUpdates") != 16
                or data.get("deadlocks") != 0 or data.get("globalAccountOrderVerified") is not True
                or data.get("originalBusinessDataUnchanged") is not True
                or data.get("serviceAndDatabaseResultsAgree") is not True):
            raise RuntimeError("Member lock regression did not prove sixteen independent committed updates")
        summary = {"formatVersion": 1, "passed": True, "verifiedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                   "runDirectory": str(run.relative_to(ROOT)), "report": str(report.relative_to(ROOT)),
                   "rounds": 8, "successfulMemberUpdates": 16, "deadlocks": 0, "realLedgerRegistrations": 0,
                   "originalBusinessDataUnchanged": True, "globalAccountOrderVerified": True,
                   "credentialFileUnchanged": True, "envFileUnchanged": True,
                   "fixtureCredentialsRetainedOnlyInMemory": True,
                   "businessWorkersDisabled": True,
                   "classpathSha256": hashlib.sha256(dependencies.encode()).hexdigest()}
        private_json(run / "verify.json", summary)
        private_json(WORK / "latest.json", {"verified_at": summary["verifiedAt"], "run_directory": summary["runDirectory"], "report": str((run / "verify.json").relative_to(ROOT))})
    print("[PASS] T026 member lock regression report: " + str((run / "verify.json").relative_to(ROOT)))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
