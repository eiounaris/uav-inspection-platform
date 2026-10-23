#!/usr/bin/env python3
"""Verify actual Flyway migrations, demo seed repeatability and MySQL constraints."""

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

from backend_env import ROOT, backend_environment, java21

WORK = ROOT / ".local/database-probe"
PROBE = "com.uav.inspection.database.probe.DatabaseProbe"


def main():
    environment = backend_environment()
    environment["TZ"] = "Asia/Shanghai"
    java = java21(environment)
    classpath_file = ROOT / "backend/target/database-probe.classpath"
    if not classpath_file.is_file():
        raise RuntimeError("Build backend/pom.xml with -P database-probe clean verify first")
    credentials = Path(environment["DEMO_CREDENTIALS_PATH"])
    if not credentials.is_file():
        raise RuntimeError("Run init-demo-credentials.py first")
    credential_hash = hashlib.sha256(credentials.read_bytes()).hexdigest()
    name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    run = WORK / name
    run.mkdir(parents=True, mode=0o700)
    classpath = os.pathsep.join([str(ROOT / "backend/target/test-classes"),
                                str(ROOT / "backend/target/classes"), classpath_file.read_text().strip()])
    reports = {}
    print("Run directory: " + str(run.relative_to(ROOT)), flush=True)
    for phase in ("migrate", "seed", "verify"):
        report = run / (phase + ".json")
        arguments = [java, "-Duser.timezone=Asia/Shanghai", "-cp", classpath, PROBE, phase, str(report)]
        if phase == "verify":
            arguments.append(str(run / "seed.json"))
        # Logs stay local; they can contain JDBC diagnostics and are not copied to tool output.
        log = run / (phase + ".log")
        descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            result = subprocess.run(arguments, env=environment, cwd=ROOT, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=150)
        if result.returncode:
            raise RuntimeError("Database phase " + phase + " failed; inspect " + str(log.relative_to(ROOT)))
        if not report.is_file():
            raise RuntimeError("Database phase " + phase + " did not create its report")
        reports[phase] = json.loads(report.read_text())
        if reports[phase].get("passed") is not True:
            raise RuntimeError("Database phase " + phase + " did not report a passing result")
        print("[PASS] Database " + phase + " phase", flush=True)
    if hashlib.sha256(credentials.read_bytes()).hexdigest() != credential_hash:
        raise RuntimeError("Demo credential file changed during repeated initialization")
    summary = {"verified_at": dt.datetime.now(dt.timezone.utc).isoformat(),
               "run_directory": str(run.relative_to(ROOT)),
               "credential_sha256_unchanged": True,
               "reports": {phase: str((run / (phase + ".json")).relative_to(ROOT)) for phase in reports}}
    (WORK / "latest.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("[PASS] Credential file preserved; reports: " + str(run.relative_to(ROOT)))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
