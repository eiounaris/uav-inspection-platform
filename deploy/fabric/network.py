#!/usr/bin/env python3
"""Manage this project's Fabric network, retaining identities and ledger volumes."""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from prepare import LOCK, NETWORK, ROOT, TOOLS, WORK, check_materials

COMPOSE = ["docker", "compose", "--project-name", "uav-inspection-fabric",
           "-f", str(ROOT / "deploy/fabric/compose.yml")]
RUNTIME_ENV = WORK / "chaincode/runtime.env"
CHANNEL = LOCK["channel"]
ORDERER_TLS = NETWORK / "organizations/ordererOrganizations/example.com/orderers/orderer.example.com/tls"


def execute(arguments, env=None):
    result = subprocess.run([str(arg) for arg in arguments], env=env, capture_output=True,
                            text=True, timeout=60, cwd=ROOT)
    if result.returncode:
        raise RuntimeError(Path(str(arguments[0])).name + " failed: " + result.stderr[-1800:])
    return result.stdout.strip()


def compose(*arguments):
    # Keep Compose startup output visible without exposing identity contents.
    command = list(COMPOSE)
    if RUNTIME_ENV.exists():
        command.extend(["--env-file", str(RUNTIME_ENV), "-f",
                        str(ROOT / "deploy/fabric/chaincode.compose.yml")])
    subprocess.run([*command, *arguments], check=True, cwd=ROOT)


def peer(org, *arguments, user="Admin"):
    base = NETWORK / f"organizations/peerOrganizations/org{org}.example.com"
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("CORE_", "FABRIC_"))}
    env.update(FABRIC_CFG_PATH=str(TOOLS / "config"), FABRIC_LOGGING_SPEC="WARN",
               CORE_PEER_LOCALMSPID=f"Org{org}MSP",
               CORE_PEER_MSPCONFIGPATH=str(base / f"users/{user}@org{org}.example.com/msp"),
               CORE_PEER_ADDRESS=f"localhost:{7051 if org == 1 else 9051}",
               CORE_PEER_TLS_ENABLED="true",
               CORE_PEER_TLS_ROOTCERT_FILE=str(base / f"peers/peer0.org{org}.example.com/tls/ca.crt"))
    return execute([TOOLS / "bin/peer", *arguments], env)


def orderer(command, *arguments):
    output = execute([TOOLS / "bin/osnadmin", "channel", command, "--no-status",
                      "-o", "localhost:7053", "--ca-file", ORDERER_TLS / "ca.crt",
                      "--client-cert", ORDERER_TLS / "server.crt",
                      "--client-key", ORDERER_TLS / "server.key", *arguments])
    return json.loads(output)


def joined_channels(org):
    output = peer(org, "channel", "list")
    return [line.strip() for line in output.splitlines() if line.strip() == CHANNEL]


def channel_info(org):
    output = peer(org, "channel", "getinfo", "-c", CHANNEL)
    start = output.find("{")
    if start < 0:
        raise RuntimeError(f"Org{org} returned no blockchain info")
    return json.loads(output[start:])


def create_channel():
    check_materials()
    overview = orderer("list")
    if overview.get("systemChannel") is not None:
        raise RuntimeError("Expected an orderer without a system channel")
    if CHANNEL not in [channel["name"] for channel in overview.get("channels") or []]:
        result = orderer("join", "-c", CHANNEL, "-b", NETWORK / f"channel-artifacts/{CHANNEL}.block")
        if result.get("name") != CHANNEL:
            raise RuntimeError("Orderer did not accept the expected channel")
        print("[PASS] Orderer accepted " + CHANNEL, flush=True)
    for attempt in range(30):
        info = orderer("list", "-c", CHANNEL)
        if info.get("status") == "active" and info.get("height", 0) >= 1:
            break
        time.sleep(1)
    else:
        raise RuntimeError("Orderer channel did not become active")
    for org in (1, 2):
        if CHANNEL not in joined_channels(org):
            peer(org, "channel", "join", "-b", NETWORK / f"channel-artifacts/{CHANNEL}.block")
        print(f"[PASS] Org{org}MSP joined {CHANNEL}", flush=True)


def decode_block(path):
    return json.loads(execute([TOOLS / "bin/configtxlator", "proto_decode", "--input", path,
                               "--type", "common.Block"]))


def verify(output, compare=None):
    materials = check_materials()
    for attempt in range(30):
        information = [channel_info(org) for org in (1, 2)]
        ordering = orderer("list", "-c", CHANNEL)
        if (information[0] == information[1] and information[0].get("height", 0) >= 1
                and ordering.get("status") == "active"
                and ordering.get("consensusRelation") == "consenter"
                and ordering.get("height") == information[0]["height"]):
            break
        time.sleep(1)
    else:
        raise RuntimeError("Peer/orderer ledgers have not converged")
    original = decode_block(NETWORK / f"channel-artifacts/{CHANNEL}.block")
    peer_results = {}
    for org, info in zip((1, 2), information):
        if joined_channels(org) != [CHANNEL]:
            raise RuntimeError(f"Org{org} is not joined to the expected channel")
        fetched = NETWORK / f"channel-artifacts/org{org}-fetched-block0.pb"
        peer(org, "channel", "fetch", "0", fetched, "-c", CHANNEL, "-o", "localhost:7050",
             "--ordererTLSHostnameOverride", "orderer.example.com", "--tls",
             "--cafile", ORDERER_TLS / "ca.crt")
        block = decode_block(fetched)
        if block["header"] != original["header"]:
            raise RuntimeError("Fetched genesis header differs from the original channel artifact")
        peer_results[f"Org{org}MSP"] = {**info, "genesis_header": block["header"]}
    config = original["data"]["data"][0]["payload"]["data"]["config"]["channel_group"]["groups"]
    if set(config["Application"]["groups"]) != {"Org1MSP", "Org2MSP"}:
        raise RuntimeError("Unexpected business organizations in the channel")
    consensus = config["Orderer"]["values"]["ConsensusType"]["value"]
    if consensus["type"] != "etcdraft" or len(consensus["metadata"]["consenters"]) != 1:
        raise RuntimeError("Expected a single-node Raft channel")
    report = {"verified_at": dt.datetime.now(dt.timezone.utc).isoformat(), "channel": CHANNEL,
              "materials": materials, "orderer": ordering, "peers": peer_results,
              "consensus": "etcdraft", "orderer_count": 1}
    if compare:
        previous = json.loads(compare.read_text())
        for key in ("channel", "materials", "peers", "consensus", "orderer_count"):
            if report[key] != previous[key]:
                raise RuntimeError("Ledger/identity snapshot changed during the preservation check: " + key)
        if report["orderer"] != previous["orderer"]:
            raise RuntimeError("Orderer channel changed during the preservation check")
        print("[PASS] Ledger heights, hashes, genesis and certificates preserved", flush=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    for org, info in peer_results.items():
        print(f"[PASS] {org} channel={CHANNEL} height={info['height']} hash={info['currentBlockHash']}")
    print(f"[PASS] Orderer active, single-node Raft, height={ordering['height']}")
    print("Report: " + str(output.relative_to(ROOT) if output.is_relative_to(ROOT) else output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("up", "stop", "restart", "remove", "status", "channel", "verify"))
    parser.add_argument("--output", type=Path, default=WORK / "status.json")
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    if args.command == "up":
        check_materials()
        compose("up", "-d", "--wait", "--wait-timeout", "180")
    elif args.command == "stop":
        compose("stop")
    elif args.command == "restart":
        check_materials()
        compose("stop")
        compose("up", "-d", "--wait", "--wait-timeout", "180")
    elif args.command == "remove":
        compose("down")
    elif args.command == "status":
        compose("ps")
    elif args.command == "channel":
        create_channel()
    else:
        verify(args.output.resolve(), args.compare.resolve() if args.compare else None)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError, KeyError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
