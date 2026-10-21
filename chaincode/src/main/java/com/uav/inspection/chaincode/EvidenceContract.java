package com.uav.inspection.chaincode;

import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.json.JSONObject;
import org.json.JSONStringer;

import java.util.regex.Pattern;

/** Registers immutable file digests; task, review and access rules remain off chain. */
@Contract(name = "EvidenceContract")
@Default
public final class EvidenceContract implements ContractInterface {

    private static final String OWNER_MSP = "Org1MSP";
    private static final String OPERATOR_MSP = "Org2MSP";
    private static final String OBJECT_TYPE = "evidence";
    private static final Pattern EVIDENCE_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]{0,9}");
    private static final Pattern FILE_HASH = Pattern.compile("[0-9a-f]{64}");

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public String RegisterEvidence(Context context, String evidenceId, String version, String fileHash) {
        String submitterMsp = context.getClientIdentity().getMSPID();
        if (!OPERATOR_MSP.equals(submitterMsp)) {
            throw forbidden();
        }
        validateEvidenceId(evidenceId);
        int versionNumber = parseVersion(version);
        if (fileHash == null || !FILE_HASH.matcher(fileHash).matches()) {
            throw new ChaincodeException("File hash must contain 64 lowercase hexadecimal characters.",
                    "INVALID_FILE_HASH");
        }

        ChaincodeStub stub = context.getStub();
        String key = stub.createCompositeKey(OBJECT_TYPE, evidenceId, version).toString();
        String existing = stub.getStringState(key);
        if (existing != null && !existing.isEmpty()) {
            if (fileHash.equals(new JSONObject(existing).getString("fileHash"))) {
                return existing;
            }
            throw new ChaincodeException("A different file hash is already registered for this evidence version.",
                    "EVIDENCE_HASH_CONFLICT");
        }

        String record = new JSONStringer().object()
                .key("evidenceId").value(evidenceId)
                .key("version").value(versionNumber)
                .key("fileHash").value(fileHash)
                .key("submitterMsp").value(submitterMsp)
                .key("registeredTxId").value(stub.getTxId())
                .endObject().toString();
        stub.putStringState(key, record);
        return record;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String QueryEvidence(Context context, String evidenceId, String version) {
        String callerMsp = context.getClientIdentity().getMSPID();
        if (!OWNER_MSP.equals(callerMsp) && !OPERATOR_MSP.equals(callerMsp)) {
            throw forbidden();
        }
        validateEvidenceId(evidenceId);
        parseVersion(version);

        ChaincodeStub stub = context.getStub();
        String key = stub.createCompositeKey(OBJECT_TYPE, evidenceId, version).toString();
        String record = stub.getStringState(key);
        if (record == null || record.isEmpty()) {
            return "{\"found\":false,\"record\":null}";
        }
        // Embed the original JSON without reserializing its fields or first txId.
        return "{\"found\":true,\"record\":" + record + "}";
    }

    private static void validateEvidenceId(String evidenceId) {
        if (evidenceId == null || !EVIDENCE_ID.matcher(evidenceId).matches()) {
            throw new ChaincodeException("Evidence identifier must be a canonical lowercase UUID.",
                    "INVALID_EVIDENCE_ID");
        }
    }

    private static int parseVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw invalidVersion();
        }
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException exception) {
            throw invalidVersion();
        }
    }

    private static ChaincodeException invalidVersion() {
        return new ChaincodeException("Version must be a canonical positive 32-bit integer.", "INVALID_VERSION");
    }

    private static ChaincodeException forbidden() {
        return new ChaincodeException("The calling organization is not permitted to perform this operation.",
                "FORBIDDEN");
    }
}
