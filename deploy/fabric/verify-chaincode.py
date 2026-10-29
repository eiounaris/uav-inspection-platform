#!/usr/bin/env python3
"""T005: exercise real chaincode with both organizations' ordinary User1 identities."""

import base64
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import uuid

from chaincode import DIRECTORY, NAME, ORDERING, committed, endorsement_peers
from network import CHANNEL, channel_info, decode_block, peer
from prepare import check_materials


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def query(org, evidence_id, version):
    return json.loads(peer(org, "chaincode", "query", "-C", CHANNEL, "-n", NAME, "-c",
                           json.dumps({"Args": ["EvidenceContract:QueryEvidence", evidence_id, str(version)]}),
                           user="User1"))


def peer_block(org, number):
    encoded = peer(org, "chaincode", "query", "-C", CHANNEL, "-n", "qscc", "-c",
                   json.dumps({"Args": ["GetBlockByNumber", CHANNEL, str(number)]}), "--hex", user="User1")
    path = DIRECTORY / f"org{org}-verified-block.pb"
    path.write_bytes(bytes.fromhex(encoded))
    return decode_block(path)


def invoke(org, evidence_id, version, file_hash):
    before = channel_info(2)["height"]
    arguments = ["EvidenceContract:RegisterEvidence", evidence_id, str(version), file_hash]
    peer(org, "chaincode", "invoke", "-C", CHANNEL, "-n", NAME, *ORDERING, *endorsement_peers(),
         "-c", json.dumps({"Args": arguments}), "--waitForEvent", "--waitForEventTimeout", "30s", user="User1")
    # Read validation flags from BOTH peers. Orderer blocks do not carry peer validation results.
    receipts = []
    for organization in (1, 2):
        block = peer_block(organization, before)
        flags = base64.b64decode(block["metadata"]["metadata"][2])
        transactions = block["data"]["data"]
        require(len(flags) == len(transactions), "Missing peer transaction validation flags")
        matches = []
        for index, transaction in enumerate(transactions):
            payload = transaction["payload"]
            for action in payload["data"].get("actions", []):
                specification = action["payload"]["chaincode_proposal_payload"]["input"]["chaincode_spec"]
                args = [base64.b64decode(arg).decode() for arg in specification["input"]["args"]]
                if specification["chaincode_id"]["name"] == NAME and args == arguments:
                    require(flags[index] == 0, "Peer marked transaction invalid")
                    require(payload["header"]["signature_header"]["creator"]["mspid"] == f"Org{org}MSP",
                            "Unexpected transaction creator")
                    matches.append(payload["header"]["channel_header"]["tx_id"])
        require(len(matches) == 1, "Expected exactly one matching transaction in this development block")
        receipts.append({"org": f"Org{organization}MSP", "block": int(block["header"]["number"]),
                         "tx_id": matches[0], "validation_code": 0})
    require(receipts[0]["tx_id"] == receipts[1]["tx_id"], "Peers disagree on committed transaction")
    return {"tx_id": receipts[0]["tx_id"], "peer_receipts": receipts}


def rejected(org, evidence_id, version, file_hash, code):
    before = [channel_info(organization) for organization in (1, 2)]
    try:
        invoke(org, evidence_id, version, file_hash)
    except RuntimeError as error:
        # Match the real chaincode response payload, not an arbitrary transport failure.
        require(re.search(r'payload:\s*"' + re.escape(code) + r'"', str(error)) is not None,
                "Expected chaincode rejection " + code + "; received: " + str(error))
    else:
        raise RuntimeError("Unexpectedly accepted " + code + " case")
    require(before == [channel_info(organization) for organization in (1, 2)],
            "A rejected endorsement unexpectedly changed the ledger")
    return {"code": code, "rejected_before_submission": True, "ledger_unchanged": True}


def main():
    check_materials()
    require(all(committed(org) for org in (1, 2)), "Deploy the chaincode first")
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    evidence_id, denied_id = str(uuid.uuid4()), str(uuid.uuid4())
    file_hash = hashlib.sha256(b"T005 UAV inspection evidence version 1\n").hexdigest()
    other_hash = hashlib.sha256(b"T005 modified evidence\n").hexdigest()
    checks = {}

    for org in (1, 2):
        require(query(org, evidence_id, 1) == {"found": False, "record": None}, "Missing key query failed")
    checks["missing_query_both_orgs"] = True
    print("[PASS] OrgA and OrgB missing queries return found=false", flush=True)

    first = invoke(2, evidence_id, 1, file_hash)
    expected = {"evidenceId": evidence_id, "version": 1, "fileHash": file_hash,
                "submitterMsp": "Org2MSP", "registeredTxId": first["tx_id"]}
    for org in (1, 2):
        require(query(org, evidence_id, 1) == {"found": True, "record": expected}, "Registered record mismatch")
    checks["orgb_register_orga_orgb_query"] = first
    print("[PASS] OrgB registered; both peers VALID; OrgA/B read identical five-field record", flush=True)

    duplicate = invoke(2, evidence_id, 1, file_hash)
    require(duplicate["tx_id"] != first["tx_id"], "Duplicate test did not submit a fresh transaction")
    duplicate_block = peer_block(2, duplicate["peer_receipts"][0]["block"])
    for transaction in duplicate_block["data"]["data"]:
        for action in transaction["payload"]["data"].get("actions", []):
            namespaces = action["payload"]["action"]["proposal_response_payload"]["extension"]["results"]["ns_rwset"]
            require(all(not namespace["rwset"]["writes"] for namespace in namespaces
                        if namespace["namespace"] == NAME), "Idempotent call wrote evidence state")
    for org in (1, 2):
        require(query(org, evidence_id, 1) == {"found": True, "record": expected}, "First transaction ID changed")
    checks["same_hash_idempotent"] = {**duplicate, "original_tx_id_preserved": True, "evidence_writes": 0}
    print("[PASS] Fresh duplicate transaction VALID; evidence writes=0; first registeredTxId retained", flush=True)

    checks["different_hash_conflict"] = rejected(2, evidence_id, 1, other_hash, "EVIDENCE_HASH_CONFLICT")
    checks["orga_write_denied"] = rejected(1, denied_id, 1, file_hash, "FORBIDDEN")
    require(query(1, denied_id, 1) == {"found": False, "record": None}, "Denied write left a record")
    checks["invalid_version"] = rejected(2, evidence_id, "01", file_hash, "INVALID_VERSION")
    for org in (1, 2):
        require(query(org, evidence_id, 1) == {"found": True, "record": expected}, "Rejected calls changed original")
    print("[PASS] Hash conflict, OrgA write and noncanonical version rejected by chaincode; ledger unchanged", flush=True)

    second = invoke(2, evidence_id, 2, other_hash)
    expected_v2 = {**expected, "version": 2, "fileHash": other_hash, "registeredTxId": second["tx_id"]}
    for org in (1, 2):
        require(query(org, evidence_id, 1) == {"found": True, "record": expected}, "Version 2 overwrote version 1")
        require(query(org, evidence_id, 2) == {"found": True, "record": expected_v2}, "Version 2 mismatch")
    checks["versions_isolated"] = second
    print("[PASS] Version 2 independently registered; version 1 unchanged", flush=True)

    report = {"verified_at": dt.datetime.now(dt.timezone.utc).isoformat(), "task": "T005", "channel": CHANNEL,
              "chaincode": NAME, "identity": "User1 (both organizations; no Admin for business calls)",
              "evidence_id": evidence_id, "records": [expected, expected_v2], "checks": checks,
              "final_peers": {f"Org{org}MSP": channel_info(org) for org in (1, 2)}}
    (DIRECTORY / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Report: .local/fabric/chaincode/verification.json")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError, KeyError) as error:
        print("[FAIL] " + str(error), file=sys.stderr)
        sys.exit(1)
