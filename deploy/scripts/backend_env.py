"""Literal local configuration for backend launchers; never execute .env as shell code."""

import os
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def backend_environment():
    values = {}
    path = ROOT / ".env"
    if path.exists():
        for number, raw in enumerate(path.read_text().splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            key, separator, value = line.partition("=")
            if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) or key in values:
                raise RuntimeError(f"Invalid local .env entry at line {number}")
            if value.startswith(("'", '"')):
                if len(value) < 2 or value[-1] != value[0]:
                    raise RuntimeError(f"Invalid quoted .env entry at line {number}")
                value = value[1:-1]
            else:
                value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
            values[key] = value
    environment = dict(os.environ)
    # Copy only backend settings, without unrelated service secrets.
    for key, value in values.items():
        if key.startswith(("DB_", "S3_", "UPLOAD_", "VERIFICATION_", "RABBITMQ_", "MESSAGING_", "FABRIC_")) or key in ("DEMO_CREDENTIALS_PATH", "JWT_SECRET"):
            environment.setdefault(key, value)
    if not environment.get("DB_PASSWORD"):
        raise RuntimeError("DB_PASSWORD is required in process env or the local .env file")
    if not re.fullmatch(r"[0-9a-f]{64}", environment.get("JWT_SECRET", "")):
        raise RuntimeError("JWT_SECRET must be the original 64-character hex key in process env or .env")
    demo_path = Path(environment.get("DEMO_CREDENTIALS_PATH") or ".local/demo-credentials.json")
    environment["DEMO_CREDENTIALS_PATH"] = str(
        (demo_path if demo_path.is_absolute() else ROOT / demo_path).resolve())
    return environment


def fabric_environment(environment, local=True):
    """Resolve explicit Fabric settings; only local runs may reuse prepared User1 paths."""
    result = dict(environment)
    defaults = {"FABRIC_CHANNEL": "mychannel", "FABRIC_CHAINCODE": "evidence",
                "FABRIC_CONTRACT": "EvidenceContract"}
    for organization, number, port in (("ORGA", 1, 7051), ("ORGB", 2, 9051)):
        defaults.update({f"FABRIC_{organization}_MSP_ID": f"Org{number}MSP",
                         f"FABRIC_{organization}_GATEWAY_ENDPOINT": f"localhost:{port}",
                         f"FABRIC_{organization}_TLS_SERVER_NAME": f"peer0.org{number}.example.com"})
    for key, value in defaults.items():
        if not result.get(key):
            result[key] = value
    paths = [f"FABRIC_{org}_{field}" for org in ("ORGA", "ORGB")
             for field in ("CERT_PATH", "KEY_PATH", "TLS_CERT_PATH")]
    if local and any(not result.get(key) for key in paths):
        manifest = Path(result.get("FABRIC_IDENTITY_MANIFEST") or ".local/fabric/gateway-paths.json")
        if not manifest.is_absolute():
            manifest = ROOT / manifest
        if not manifest.is_file():
            raise RuntimeError("Prepared Fabric identity manifest is missing; prepare the network or set identity paths")
        values = json.loads(manifest.read_text())
        expected = {f"FABRIC_{org}_{field}" for org in ("ORGA", "ORGB")
                    for field in ("MSP_ID", "GATEWAY_ENDPOINT", "CERT_PATH", "KEY_PATH", "TLS_CERT_PATH")}
        if (not isinstance(values, dict) or set(values) != expected
                or any(not isinstance(value, str) or not value.strip() for value in values.values())):
            raise RuntimeError("Unexpected Fabric identity manifest")
        for org, number in (("ORGA", 1), ("ORGB", 2)):
            if values[f"FABRIC_{org}_MSP_ID"] != f"Org{number}MSP":
                raise RuntimeError("Unexpected prepared Fabric institution mapping")
            expected_user = (ROOT / ".local/fabric/network/organizations/peerOrganizations"
                             / f"org{number}.example.com/users/User1@org{number}.example.com/msp").resolve()
            for field in ("CERT_PATH", "KEY_PATH"):
                value = values[f"FABRIC_{org}_{field}"]
                if not isinstance(value, str) or not Path(value).resolve().is_relative_to(expected_user):
                    raise RuntimeError("Prepared Fabric manifest must reference ordinary User1 identities")
        for key in paths:
            if not result.get(key):
                result[key] = values[key]
    for key in paths:
        if not result.get(key):
            raise RuntimeError("Explicit Fabric identity path is required: " + key)
        path = Path(result[key])
        path = (path if path.is_absolute() else ROOT / path).resolve()
        if not path.is_file():
            raise RuntimeError("Fabric identity file is missing for " + key)
        result[key] = str(path)
    return result


def java21(environment):
    java = str(Path(environment["JAVA_HOME"]) / "bin/java") if environment.get("JAVA_HOME") else shutil.which("java")
    if not java:
        raise RuntimeError("Select JDK 21 using JAVA_HOME")
    result = subprocess.run([java, "-XshowSettings:properties", "-version"],
                            capture_output=True, text=True, check=True, timeout=15)
    if not re.search(r"java.specification.version\s*=\s*21\s", result.stderr):
        raise RuntimeError("Select JDK 21 using JAVA_HOME")
    return java
