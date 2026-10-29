#!/usr/bin/env python3
"""Prepare pinned Fabric tools and development identities without resetting a ledger."""

import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

from settings import SETTINGS, check_namespace

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / ".local/fabric"
TOOLS = WORK / "tools"
NETWORK = WORK / "network"
LOCK = json.loads((ROOT / "deploy/fabric/versions.json").read_text())


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def certificate_fingerprint():
    orgs = NETWORK / "organizations"
    certificates = sorted(p for p in orgs.rglob("*")
                          if p.is_file() and (p.suffix == ".crt" or "signcerts" in p.parts))
    if not certificates:
        raise RuntimeError("Development certificates are missing; automatic regeneration is refused")
    digest = hashlib.sha256()
    for path in certificates:
        digest.update(str(path.relative_to(orgs)).encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def check_materials():
    marker = NETWORK / "materials.json"
    if not marker.is_file():
        raise RuntimeError("Run prepare.py before starting this network")
    state = json.loads(marker.read_text())
    check_namespace(state)
    for key in ("fabric_version", "samples_commit", "channel"):
        if state.get(key) != LOCK[key]:
            raise RuntimeError("Pinned versions differ from existing identities; keep the existing network intact")
    block = NETWORK / "channel-artifacts" / (LOCK["channel"] + ".block")
    if not block.is_file() or sha256(block) != state["genesis_sha256"]:
        raise RuntimeError("Genesis artifact is missing or changed; automatic replacement is refused")
    if certificate_fingerprint() != state["certificate_fingerprint"]:
        raise RuntimeError("Existing certificates changed; automatic regeneration is refused")
    for node in (NETWORK / "organizations").glob("**/tls"):
        if ("peers" in node.parts or "orderers" in node.parts) and not (node / "server.key").is_file():
            raise RuntimeError("A node TLS key is missing; restore the original identities")
    return state


def fetch(name, url, expected):
    directory = WORK / "downloads"
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / name
    if not destination.exists():
        temporary = destination.with_suffix(destination.suffix + ".part")
        print("Downloading pinned archive: " + name, flush=True)
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open("wb") as target:
            shutil.copyfileobj(source, target)
        if sha256(temporary) != expected:
            raise RuntimeError("Downloaded archive SHA-256 does not match the lock file")
        temporary.replace(destination)
    if sha256(destination) != expected:
        raise RuntimeError("Cached archive SHA-256 does not match the lock file: " + name)
    print("[PASS] archive SHA-256: " + name, flush=True)
    return destination


def copy_member(archive, member, destination, executable=False):
    if not member.isfile():
        raise RuntimeError("Expected a regular archive file")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with archive.extractfile(member) as source, destination.open("wb") as target:
        shutil.copyfileobj(source, target)
    destination.chmod(0o755 if executable else 0o600)


def install_tools(binary_archive):
    marker = TOOLS / "installed.json"
    needed = ("peer", "cryptogen", "configtxgen", "configtxlator", "osnadmin")
    if not marker.exists() or any(not (TOOLS / "bin" / tool).is_file() for tool in needed):
        with tarfile.open(binary_archive) as archive:
            for member in archive.getmembers():
                path = Path(member.name)
                if (member.isfile() and not path.is_absolute() and ".." not in path.parts
                        and path.parts and path.parts[0] in ("bin", "config")):
                    copy_member(archive, member, TOOLS / path, path.parts[0] == "bin")
        marker.write_text(json.dumps({"archive_sha256": LOCK["binary_sha256"]}) + "\n")
    if json.loads(marker.read_text())["archive_sha256"] != LOCK["binary_sha256"]:
        raise RuntimeError("Installed Fabric tools have a different pinned version")
    result = subprocess.run([str(TOOLS / "bin/peer"), "version"], check=True,
                            capture_output=True, text=True)
    if not re.search(r"(?m)^\s*Version:\s*v?" + re.escape(LOCK["fabric_version"]) + r"\s*$", result.stdout):
        raise RuntimeError("Fabric CLI version differs from the pinned runtime")
    print(result.stdout.strip(), flush=True)


def prepare_peer_config():
    # Fabric's default external CaaS builder is retained; disable the Docker backend.
    # This is the supported no-Docker configuration documented in core.yaml.
    source = (TOOLS / "config/core.yaml").read_text()
    original = "    endpoint: unix:///var/run/docker.sock"
    if source.count(original) != 1:
        raise RuntimeError("Pinned Fabric VM configuration has an unexpected structure")
    configured = source.replace(original, '    endpoint: ""')
    target = NETWORK / "peercfg/core.yaml"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.read_text() != configured:
        raise RuntimeError("Existing peer configuration differs; automatic replacement is refused")
    if not target.exists():
        target.write_text(configured)


def prepare():
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        raise RuntimeError("This development baseline pins Linux amd64 tools")
    os.umask(0o077)
    if (NETWORK / "materials.json").exists():
        check_materials()  # Reject namespace drift before touching existing local material.
    WORK.mkdir(parents=True, exist_ok=True)
    binary = fetch(LOCK["binary_archive"],
                   "https://github.com/hyperledger/fabric/releases/download/v" + LOCK["fabric_version"]
                   + "/" + LOCK["binary_archive"], LOCK["binary_sha256"])
    install_tools(binary)
    prepare_peer_config()
    if (NETWORK / "materials.json").exists():
        check_materials()
        print("[PASS] Existing identities and genesis preserved; no material regenerated")
        return
    if (NETWORK / "organizations").exists() or (NETWORK / "channel-artifacts").exists():
        raise RuntimeError("Incomplete initial material exists; inspect it before proceeding. Nothing was deleted")
    samples = fetch(LOCK["samples_archive"],
                    "https://codeload.github.com/hyperledger/fabric-samples/tar.gz/" + LOCK["samples_commit"],
                    LOCK["samples_sha256"])
    prefix = "fabric-samples-" + LOCK["samples_commit"] + "/"
    paths = ["test-network/configtx/configtx.yaml"] + [
        "test-network/organizations/cryptogen/crypto-config-" + org + ".yaml"
        for org in ("org1", "org2", "orderer")]
    with tarfile.open(samples) as archive:
        for relative in paths:
            copy_member(archive, archive.getmember(prefix + relative),
                        NETWORK / relative.removeprefix("test-network/"))
        copy_member(archive, archive.getmember(prefix + "LICENSE"), WORK / "SAMPLES-LICENSE")
    # The shared samples also define extra BFT orderers; this project uses one Raft node.
    orderer_config = NETWORK / "organizations/cryptogen/crypto-config-orderer.yaml"
    orderer_text = orderer_config.read_text()
    delimiter = "      - Hostname: orderer2"
    if delimiter not in orderer_text:
        raise RuntimeError("Pinned samples orderer configuration has an unexpected structure")
    orderer_config.write_text(orderer_text.split(delimiter, 1)[0])
    # Include anchor peers in genesis, avoiding separate channel-update transactions.
    config = NETWORK / "configtx/configtx.yaml"
    text = config.read_text()
    for org, port in ((1, 7051), (2, 9051)):
        original = f"    MSPDir: ../organizations/peerOrganizations/org{org}.example.com/msp"
        if text.count(original) != 1:
            raise RuntimeError("Pinned samples organization configuration has an unexpected structure")
        text = text.replace(original, original + f"\n    AnchorPeers:\n      - Host: peer0.org{org}.example.com\n        Port: {port}")
    config.write_text(text)
    env = dict(os.environ, FABRIC_CFG_PATH=str(NETWORK / "configtx"))
    for org in ("org1", "org2", "orderer"):
        subprocess.run([str(TOOLS / "bin/cryptogen"), "generate",
                        "--config=" + str(NETWORK / f"organizations/cryptogen/crypto-config-{org}.yaml"),
                        "--output=" + str(NETWORK / "organizations")], check=True, env=env)
    artifacts = NETWORK / "channel-artifacts"
    artifacts.mkdir()
    block = artifacts / (LOCK["channel"] + ".block")
    subprocess.run([str(TOOLS / "bin/configtxgen"), "-profile", "ChannelUsingRaft",
                    "-channelID", LOCK["channel"], "-outputBlock", str(block)], check=True, env=env)
    gateway = {}
    for org, label in ((1, "ORGA"), (2, "ORGB")):
        base = NETWORK / f"organizations/peerOrganizations/org{org}.example.com"
        user = base / f"users/User1@org{org}.example.com/msp"
        keys = list((user / "keystore").glob("*"))
        certs = list((user / "signcerts").glob("*"))
        if len(keys) != 1 or len(certs) != 1:
            raise RuntimeError("Expected exactly one application key and certificate per organization")
        gateway.update({f"FABRIC_{label}_MSP_ID": f"Org{org}MSP",
                        f"FABRIC_{label}_GATEWAY_ENDPOINT": SETTINGS.peer_endpoint(org),
                        f"FABRIC_{label}_CERT_PATH": str(certs[0]),
                        f"FABRIC_{label}_KEY_PATH": str(keys[0]),
                        f"FABRIC_{label}_TLS_CERT_PATH": str(base / f"peers/peer0.org{org}.example.com/tls/ca.crt")})
    (WORK / "gateway-paths.json").write_text(json.dumps(gateway, indent=2) + "\n")
    state = {key: LOCK[key] for key in ("fabric_version", "samples_commit", "channel")}
    state["deployment_namespace"] = SETTINGS.namespace()
    state.update(genesis_sha256=sha256(block), certificate_fingerprint=certificate_fingerprint())
    (NETWORK / "materials.json").write_text(json.dumps(state, indent=2) + "\n")
    check_materials()
    print("[PASS] Two peer organizations, one orderer and genesis prepared; no ledger was reset")


if __name__ == "__main__":
    try:
        prepare()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
