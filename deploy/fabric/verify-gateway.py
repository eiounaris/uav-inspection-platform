#!/usr/bin/env python3
"""Run T006 Java Gateway phases in separate JVMs, keeping recoverable checkpoints."""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / ".local/fabric/gateway-probe"
PROBE = "com.uav.inspection.blockchain.probe.FabricGatewayProbe"
PHASES = ("prepare", "submit", "confirm", "verify")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("all", *PHASES), default="all")
    parser.add_argument("--run-dir", type=Path)
    args = parser.parse_args()
    if args.phase not in ("all", "prepare") and args.run_dir is None:
        parser.error("--run-dir is required when resuming a phase")
    manifest = ROOT / ".local/fabric/gateway-paths.json"
    classpath_file = ROOT / "backend/target/fabric-probe.classpath"
    compiled_probe = ROOT / ("backend/target/test-classes/" + PROBE.replace(".", "/") + ".class")
    if not manifest.is_file():
        raise RuntimeError("Prepare the Fabric network first; gateway-paths.json is missing")
    if not classpath_file.is_file() or not compiled_probe.is_file():
        raise RuntimeError("Build with JDK 21: mvn -f backend/pom.xml "
                           '-Dmaven.repo.local="$PWD/.local/m2" -P fabric-probe clean verify')
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
    if not java:
        raise RuntimeError("JDK 21 java executable is required")
    version = subprocess.run([java, "-XshowSettings:properties", "-version"],
                             capture_output=True, text=True, check=True, timeout=15)
    if not re.search(r"java.specification.version\s*=\s*21\s", version.stderr):
        raise RuntimeError("Select JDK 21 using JAVA_HOME before running the Gateway probe")
    # Use the project's generated ordinary User1 identities, without sourcing .env or reading keys.
    paths = json.loads(manifest.read_text())
    expected_keys = {f"FABRIC_{org}_{field}" for org in ("ORGA", "ORGB")
                     for field in ("MSP_ID", "GATEWAY_ENDPOINT", "CERT_PATH", "KEY_PATH", "TLS_CERT_PATH")}
    if set(paths) != expected_keys:
        raise RuntimeError("Unexpected gateway identity path manifest")
    for org, number in (("ORGA", 1), ("ORGB", 2)):
        if paths[f"FABRIC_{org}_MSP_ID"] != f"Org{number}MSP":
            raise RuntimeError("Unexpected organization mapping")
        expected_user = (ROOT / ".local/fabric/network/organizations/peerOrganizations"
                         / f"org{number}.example.com/users/User1@org{number}.example.com/msp").resolve()
        for field in ("CERT_PATH", "KEY_PATH"):
            path = Path(paths[f"FABRIC_{org}_{field}"]).resolve()
            if not path.is_relative_to(expected_user) or not path.is_file():
                raise RuntimeError("Probe requires the prepared User1 identity files")
    environment = dict(os.environ)
    environment.update(paths)
    environment.update(FABRIC_CHANNEL="mychannel", FABRIC_CHAINCODE="evidence", FABRIC_CONTRACT="EvidenceContract",
                       FABRIC_ORGA_TLS_SERVER_NAME="peer0.org1.example.com",
                       FABRIC_ORGB_TLS_SERVER_NAME="peer0.org2.example.com")
    run_directory = (args.run_dir if args.run_dir else WORK / (
        dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])).resolve()
    if not run_directory.is_relative_to(WORK.resolve()):
        raise RuntimeError("Keep probe checkpoints under .local/fabric/gateway-probe/")
    run_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(run_directory, 0o700)
    dependencies = classpath_file.read_text().strip()
    classpath = os.pathsep.join([str(ROOT / "backend/target/test-classes"),
                                str(ROOT / "backend/target/classes"), dependencies])
    phase_list = PHASES if args.phase == "all" else (args.phase,)
    print("Run directory: " + str(run_directory.relative_to(ROOT)), flush=True)
    for phase in phase_list:
        subprocess.run([java, "-cp", classpath, PROBE, phase, str(run_directory)],
                       env=environment, cwd=ROOT, check=True, timeout=120)
    if args.phase in ("all", "verify"):
        report = run_directory / "verification.json"
        if not report.is_file():
            raise RuntimeError("Java verification report is missing")
        metadata = {"verified_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                    "report": str(report.relative_to(ROOT)), "java": java,
                    "classpath_sha256": hashlib.sha256(dependencies.encode()).hexdigest(),
                    "gateway_libraries": [Path(item).name for item in dependencies.split(os.pathsep)
                                          if any(name in Path(item).name for name in
                                                 ("fabric-gateway-", "fabric-protos-", "grpc-api-",
                                                  "grpc-netty-shaded-", "protobuf-java-"))]}
        (WORK / "latest.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print("[PASS] T006 Java Gateway report: " + metadata["report"])


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
