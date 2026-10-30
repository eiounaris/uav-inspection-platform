#!/usr/bin/env python3
"""Deploy the minimal evidence chaincode without a Docker socket in any peer."""

import argparse
import base64
import datetime as dt
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile

from network import (CHANNEL, NETWORK, ORDERER_TLS, ROOT, RUNTIME_ENV, TOOLS, WORK,
                     compose, execute, joined_channels, peer)
from prepare import check_materials
from settings import SETTINGS

NAME = "evidence"
VERSION = "0.1.0"
SEQUENCE = "1"
POLICY = "AND('Org1MSP.peer','Org2MSP.peer')"
IMAGE = SETTINGS.chaincode_image
DIRECTORY = WORK / "chaincode"
ORDERING = ["-o", SETTINGS.orderer_endpoint, "--ordererTLSHostnameOverride", "orderer.example.com",
            "--tls", "--cafile", str(ORDERER_TLS / "ca.crt")]
DEFINITION = ["--channelID", CHANNEL, "--name", NAME, "--version", VERSION,
              "--sequence", SEQUENCE, "--signature-policy", POLICY]


def endorsement_peers():
    arguments = []
    for org in (1, 2):
        ca = NETWORK / (f"organizations/peerOrganizations/org{org}.example.com/"
                        f"peers/peer0.org{org}.example.com/tls/ca.crt")
        arguments.extend(["--peerAddresses", SETTINGS.peer_endpoint(org), "--tlsRootCertFiles", str(ca)])
    return arguments


def archive(files):
    """Stable package bytes make calculatepackageid repeatable across runs."""
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as tar:
            for name, contents in files.items():
                entry = tarfile.TarInfo(name)
                entry.size = len(contents)
                entry.mode = 0o644
                tar.addfile(entry, io.BytesIO(contents))
    return output.getvalue()


def make_package(org):
    directory = DIRECTORY / f"org{org}"
    directory.mkdir(parents=True, exist_ok=True)
    connection = {"address": f"evidence-org{org}:9999", "dial_timeout": "10s", "tls_required": False}
    metadata = {"path": "", "type": "ccaas", "label": "evidence_0.1.0"}
    package = directory / "package.tar.gz"
    package.write_bytes(archive({
        "metadata.json": json.dumps(metadata, separators=(",", ":")).encode(),
        "code.tar.gz": archive({"connection.json": json.dumps(connection, separators=(",", ":")).encode()})
    }))
    package_id = peer(org, "lifecycle", "chaincode", "calculatepackageid", package)
    if not package_id.startswith("evidence_0.1.0:"):
        raise RuntimeError("Unexpected package ID")
    return package, package_id


def committed(org):
    result = json.loads(peer(org, "lifecycle", "chaincode", "querycommitted", "-C", CHANNEL,
                             "--output", "json"))
    definitions = [item for item in result.get("chaincode_definitions", []) if item["name"] == NAME]
    if len(definitions) > 1:
        raise RuntimeError("Multiple evidence definitions returned")
    if definitions and (definitions[0]["version"] != VERSION or int(definitions[0]["sequence"]) != 1):
        raise RuntimeError("Unexpected existing chaincode definition; automatic upgrade is refused")
    if definitions:
        definition = definitions[0]
        if (definition.get("endorsement_plugin") != "escc"
                or definition.get("validation_plugin") != "vscc"
                or definition.get("init_required", False) is not False
                or definition.get("collections") not in (None, {}, {"config": []})):
            raise RuntimeError("Unexpected chaincode plugins, initialization or private collections; "
                               "automatic replacement is refused")
    return definitions[0] if definitions else None


def verified_policy(definition, org):
    """Check the actual ledger policy before modifying an existing runtime."""
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    raw = DIRECTORY / f"org{org}-policy.pb"
    raw.write_bytes(base64.b64decode(definition["validation_parameter"], validate=True))
    policy = json.loads(execute([TOOLS / "bin/configtxlator", "proto_decode", "--input", raw,
                                 "--type", "protos.ApplicationPolicy"]))
    signature = policy.get("signature_policy", {})
    rule = signature.get("rule", {}).get("n_out_of", {})
    identities = signature.get("identities", [])
    if (len(identities) != 2 or int(rule.get("n", 0)) != 2
            or rule.get("rules") != [{"signed_by": 0}, {"signed_by": 1}]):
        raise RuntimeError("Committed endorsement policy is not the expected two-peer AND policy")
    principals = []
    principal_file = DIRECTORY / f"org{org}-policy-principal.pb"
    for identity in identities:
        if identity.get("principal_classification") != "ROLE":
            raise RuntimeError("Expected role principals in the committed policy")
        principal_file.write_bytes(base64.b64decode(identity["principal"], validate=True))
        principals.append(json.loads(execute([TOOLS / "bin/configtxlator", "proto_decode",
                                             "--input", principal_file, "--type", "common.MSPRole"])))
    if {(p["msp_identifier"], p["role"]) for p in principals} != {
            ("Org1MSP", "PEER"), ("Org2MSP", "PEER")}:
        raise RuntimeError("Committed endorsement policy is not the expected two-peer AND policy")
    return policy


def verify_approved_package(org, package_id):
    approved = json.loads(peer(org, "lifecycle", "chaincode", "queryapproved", "-C", CHANNEL,
                               "-n", NAME, "--sequence", SEQUENCE, "--output", "json"))
    actual = approved.get("source", {}).get("Type", {}).get("LocalPackage", {}).get("package_id")
    if actual != package_id:
        raise RuntimeError(f"Org{org} has approved a different package; refusing to overwrite it")


def lock_artifacts(jar_hash, dockerfile_hash):
    """Pin executable artifacts even if a later build or deployment fails.

    A CaaS package ID covers connection metadata, not the Java bytecode. An
    unchanged package ID therefore cannot authorize a replacement application.
    """
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    expected = {"schema_version": 1, "channel": CHANNEL, "chaincode": NAME,
                "version": VERSION, "sequence": int(SEQUENCE),
                "jar_sha256": jar_hash, "dockerfile_sha256": dockerfile_hash}
    path = DIRECTORY / "artifact-lock.json"
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        if json.loads(path.read_text()) != expected:
            raise RuntimeError("Chaincode jar or Dockerfile differs from the artifact lock; "
                               "a reviewed chaincode upgrade is required") from None
    else:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(expected, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
    return expected


def deploy():
    check_materials()
    jar = ROOT / "chaincode/target/evidence-chaincode-0.1.0.jar"
    if not jar.is_file():
        raise RuntimeError("Build chaincode/pom.xml with Maven clean package first")
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    dockerfile_hash = hashlib.sha256((ROOT / "chaincode/Dockerfile").read_bytes()).hexdigest()
    previous = DIRECTORY / "deployment.json"
    if previous.exists() and json.loads(previous.read_text())["jar_sha256"] != jar_hash:
        raise RuntimeError("The jar changed after deployment; a reviewed chaincode upgrade is required")
    existing = {}
    for org in (1, 2):
        if CHANNEL not in joined_channels(org):
            raise RuntimeError(f"Org{org} has not joined {CHANNEL}")
        existing[org] = committed(org)  # Refuse upgrades before making any change.
    packages = {}
    package_files = {}
    for org in (1, 2):
        package, package_id = make_package(org)
        packages[str(org)] = package_id
        package_files[org] = package
        if existing[org]:
            verified_policy(existing[org], org)
            verify_approved_package(org, package_id)
    # The first lock may adopt this project's already-running development
    # definition after the preceding ledger/package checks. Never replace it.
    artifacts = lock_artifacts(jar_hash, dockerfile_hash)
    for org in (1, 2):
        package_id = packages[str(org)]
        installed = json.loads(peer(org, "lifecycle", "chaincode", "queryinstalled", "--output", "json"))
        if package_id not in [item["package_id"] for item in installed.get("installed_chaincodes", [])]:
            peer(org, "lifecycle", "chaincode", "install", package_files[org])
        print(f"[PASS] Org{org} installed {package_id}", flush=True)

    # A local image build uses only the verified jar and a digest-pinned JRE base.
    subprocess.run(["docker", "build", "--provenance=false", "--tag", IMAGE, str(ROOT / "chaincode")],
                   check=True, cwd=ROOT)
    RUNTIME_ENV.write_text("".join(f"EVIDENCE_ORG{org}_PACKAGE_ID={packages[str(org)]}\n" for org in (1, 2)))
    compose("up", "-d", "--wait", "--wait-timeout", "120", "evidence-org1", "evidence-org2")

    for org in (1, 2):
        definition = committed(org)
        if definition:
            verified_policy(definition, org)
            verify_approved_package(org, packages[str(org)])
        else:
            peer(org, "lifecycle", "chaincode", "approveformyorg", *ORDERING, *DEFINITION,
                 "--package-id", packages[str(org)], "--waitForEvent", "--waitForEventTimeout", "30s")
        print(f"[PASS] Org{org} approved evidence sequence 1", flush=True)
    if not committed(2):
        readiness = json.loads(peer(2, "lifecycle", "chaincode", "checkcommitreadiness", *DEFINITION,
                                    "--output", "json"))
        if readiness.get("approvals") != {"Org1MSP": True, "Org2MSP": True}:
            raise RuntimeError("Both organization approvals are required")
        peer(2, "lifecycle", "chaincode", "commit", *ORDERING, *DEFINITION, *endorsement_peers(),
             "--waitForEvent", "--waitForEventTimeout", "30s")
    definitions = {f"Org{org}MSP": committed(org) for org in (1, 2)}
    if any(value is None for value in definitions.values()):
        raise RuntimeError("Committed definition missing on a peer")
    # Decode both committed policies, not only the CLI's success status.
    policies = {org: verified_policy(definitions[f"Org{org}MSP"], org) for org in (1, 2)}
    report = {"deployed_at": dt.datetime.now(dt.timezone.utc).isoformat(), "channel": CHANNEL,
              "chaincode": NAME, "version": VERSION, "sequence": 1, "packages": packages,
              "jar_sha256": jar_hash,
              "dockerfile_sha256": dockerfile_hash, "artifact_lock": artifacts,
              "image_id": execute(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"]),
              "endorsement_policy": policies[2], "definitions": definitions}
    (DIRECTORY / "deployment.json").write_text(json.dumps(report, indent=2) + "\n")
    print("[PASS] Committed evidence v0.1.0 with AND(Org1MSP.peer, Org2MSP.peer)")
    print("Report: .local/fabric/chaincode/deployment.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("deploy",))
    parser.parse_args()
    try:
        deploy()
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError, KeyError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
