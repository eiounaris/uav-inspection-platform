#!/usr/bin/env python3
"""Run real HTTP/MySQL/Redis/private-S3 device checks without printing credentials or object paths."""

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

from backend_env import ROOT, backend_environment, java21

WORK = ROOT / ".local/device-probe"
PROBE = "com.uav.inspection.device.probe.DeviceFlowProbe"


def main():
    environment = {key: value for key, value in backend_environment().items()
                   if not key.startswith("SPRING_")}
    environment["TZ"] = "Asia/Shanghai"
    java = java21(environment)
    classpath_file = ROOT / "backend/target/database-probe.classpath"
    compiled_probe = ROOT / ("backend/target/test-classes/" + PROBE.replace(".", "/") + ".class")
    if not classpath_file.is_file() or not compiled_probe.is_file():
        raise RuntimeError("Build backend/pom.xml with -P database-probe clean verify first")
    dependencies = classpath_file.read_text().strip()
    if not dependencies:
        raise RuntimeError("The database-probe dependency classpath is empty; rebuild it first")
    credentials = Path(environment["DEMO_CREDENTIALS_PATH"])
    if not credentials.is_file():
        raise RuntimeError("Run init-demo-credentials.py first")
    original_credentials = hashlib.sha256(credentials.read_bytes()).digest()
    name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    run = WORK / name
    run.mkdir(parents=True, mode=0o700)
    report, log = run / "report.json", run / "probe.log"
    classpath = os.pathsep.join([str(ROOT / "backend/target/test-classes"),
                                str(ROOT / "backend/target/classes"), dependencies])
    arguments = [java, "-Djava.awt.headless=true", "-Duser.timezone=Asia/Shanghai",
                 "-cp", classpath, PROBE, str(report)]
    print("Run directory: " + str(run.relative_to(ROOT)), flush=True)
    descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        result = subprocess.run(arguments, env=environment, cwd=ROOT, stdout=output,
                                stderr=subprocess.STDOUT, timeout=360)
    if result.returncode:
        raise RuntimeError("Device flow probe failed; inspect " + str(log.relative_to(ROOT)))
    if not report.is_file():
        raise RuntimeError("Device flow probe did not create its report")
    os.chmod(report, 0o600)
    data = json.loads(report.read_text())
    if not isinstance(data, dict) or data.get("passed") is not True:
        raise RuntimeError("Device flow probe did not report a passing result")
    if hashlib.sha256(credentials.read_bytes()).digest() != original_credentials:
        raise RuntimeError("Device flow probe changed the original demo credential file")
    summary = {"verified_at": dt.datetime.now(dt.timezone.utc).isoformat(),
               "run_directory": str(run.relative_to(ROOT)),
               "report": str(report.relative_to(ROOT)),
               "credential_file_unchanged": True,
               "classpath_sha256": hashlib.sha256(dependencies.encode()).hexdigest()}
    temporary = WORK / (".latest-" + uuid.uuid4().hex + ".tmp")
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump(summary, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, WORK / "latest.json")
    finally:
        temporary.unlink(missing_ok=True)
    print("[PASS] T015 device flow report: " + summary["report"])


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        # Messages contain only safe local paths/check names. Java diagnostics remain private.
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
