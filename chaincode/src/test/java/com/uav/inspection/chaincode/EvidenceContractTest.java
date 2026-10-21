package com.uav.inspection.chaincode;

import org.hyperledger.fabric.contract.ClientIdentity;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.CompositeKey;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvidenceContractTest {

    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_ID = "123e4567-e89b-12d3-a456-426614174001";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    private final EvidenceContract contract = new EvidenceContract();
    private Context context;
    private ClientIdentity identity;
    private ChaincodeStub stub;
    private Map<String, String> state;

    @BeforeEach
    void setUp() {
        context = mock(Context.class);
        identity = mock(ClientIdentity.class);
        stub = mock(ChaincodeStub.class);
        state = new HashMap<>();
        when(context.getClientIdentity()).thenReturn(identity);
        when(context.getStub()).thenReturn(stub);
        when(identity.getMSPID()).thenReturn("Org2MSP");
        when(stub.getTxId()).thenReturn("first-transaction-id");
        when(stub.createCompositeKey(eq("evidence"), anyString(), anyString()))
                .thenAnswer(invocation -> new CompositeKey("evidence",
                        invocation.getArgument(1, String.class), invocation.getArgument(2, String.class)));
        when(stub.getStringState(anyString()))
                .thenAnswer(invocation -> state.getOrDefault(invocation.getArgument(0, String.class), ""));
        doAnswer(invocation -> {
            state.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
            return null;
        }).when(stub).putStringState(anyString(), anyString());
    }

    @Test
    void registersOneRecordWithNumericVersionAndFirstTransactionId() {
        String result = contract.RegisterEvidence(context, ID, "1", HASH);
        JSONObject record = new JSONObject(result);

        assertEquals(Set.of("evidenceId", "version", "fileHash", "submitterMsp", "registeredTxId"),
                record.keySet());
        assertEquals(ID, record.getString("evidenceId"));
        assertInstanceOf(Number.class, record.get("version"));
        assertEquals(1, record.getInt("version"));
        assertEquals(HASH, record.getString("fileHash"));
        assertEquals("Org2MSP", record.getString("submitterMsp"));
        assertEquals("first-transaction-id", record.getString("registeredTxId"));
        String key = new CompositeKey("evidence", ID, "1").toString();
        assertEquals(Map.of(key, result), state);
        verify(stub).putStringState(key, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Org1MSP", "OtherMSP"})
    @NullAndEmptySource
    void deniesUnauthorizedRegistrationBeforeValidatingInputs(String msp) {
        when(identity.getMSPID()).thenReturn(msp);
        assertRejected("FORBIDDEN", () -> contract.RegisterEvidence(context, "invalid", "0", "invalid"));
        verifyNoInteractions(stub);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OtherMSP"})
    @NullAndEmptySource
    void deniesUnauthorizedQueryBeforeValidatingInputs(String msp) {
        when(identity.getMSPID()).thenReturn(msp);
        assertRejected("FORBIDDEN", () -> contract.QueryEvidence(context, "invalid", "0"));
        verifyNoInteractions(stub);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Org1MSP", "Org2MSP"})
    void bothOrganizationsCanQueryMissingEvidence(String msp) {
        when(identity.getMSPID()).thenReturn(msp);
        String result = contract.QueryEvidence(context, ID, "1");
        assertEquals("{\"found\":false,\"record\":null}", result);
        JSONObject response = new JSONObject(result);
        assertFalse(response.getBoolean("found"));
        assertEquals(JSONObject.NULL, response.get("record"));
        verify(stub, never()).putStringState(anyString(), anyString());
    }

    @Test
    void queryEmbedsOriginalRecordWithoutWritingState() {
        String record = contract.RegisterEvidence(context, ID, "1", HASH);
        clearInvocations(stub);
        when(identity.getMSPID()).thenReturn("Org1MSP");

        assertEquals("{\"found\":true,\"record\":" + record + "}",
                contract.QueryEvidence(context, ID, "1"));
        verify(stub, never()).putStringState(anyString(), anyString());
    }

    @Test
    void duplicateHashReturnsUnchangedRecordAndDoesNotWriteAgain() {
        String first = contract.RegisterEvidence(context, ID, "1", HASH);
        when(stub.getTxId()).thenReturn("later-transaction-id");
        clearInvocations(stub);

        String duplicate = contract.RegisterEvidence(context, ID, "1", HASH);
        assertEquals(first, duplicate);
        assertEquals("first-transaction-id", new JSONObject(duplicate).getString("registeredTxId"));
        assertEquals(1, state.size());
        verify(stub, never()).putStringState(anyString(), anyString());
    }

    @Test
    void differentHashForSameVersionFailsWithoutChangingState() {
        contract.RegisterEvidence(context, ID, "1", HASH);
        Map<String, String> original = Map.copyOf(state);
        clearInvocations(stub);

        assertRejected("EVIDENCE_HASH_CONFLICT",
                () -> contract.RegisterEvidence(context, ID, "1", OTHER_HASH));
        assertEquals(original, state);
        verify(stub, never()).putStringState(anyString(), anyString());
    }

    @Test
    void isolatesVersionsWithinOneEvidenceIdentifier() {
        contract.RegisterEvidence(context, ID, "1", HASH);
        assertFalse(new JSONObject(contract.QueryEvidence(context, ID, "2")).getBoolean("found"));
        contract.RegisterEvidence(context, ID, "2", OTHER_HASH);

        assertEquals(HASH, queryRecord(ID, "1").getString("fileHash"));
        assertEquals(OTHER_HASH, queryRecord(ID, "2").getString("fileHash"));
        assertEquals(2, state.size());
    }

    @Test
    void isolatesEvidenceIdentifiersAtTheSameVersion() {
        contract.RegisterEvidence(context, ID, "1", HASH);
        assertFalse(new JSONObject(contract.QueryEvidence(context, OTHER_ID, "1")).getBoolean("found"));
        contract.RegisterEvidence(context, OTHER_ID, "1", OTHER_HASH);

        assertEquals(HASH, queryRecord(ID, "1").getString("fileHash"));
        assertEquals(OTHER_HASH, queryRecord(OTHER_ID, "1").getString("fileHash"));
        assertEquals(2, state.size());
    }

    @Test
    void acceptsMaximumPositiveInt32Version() {
        String version = Integer.toString(Integer.MAX_VALUE);
        JSONObject registered = new JSONObject(contract.RegisterEvidence(context, ID, version, HASH));
        assertEquals(Integer.MAX_VALUE, registered.getInt("version"));
        assertEquals(Integer.MAX_VALUE, queryRecord(ID, version).getInt("version"));
    }

    @ParameterizedTest
    @MethodSource("invalidEvidenceIds")
    void rejectsNonCanonicalEvidenceIdentifiersWithoutLedgerAccess(String evidenceId) {
        assertRejected("INVALID_EVIDENCE_ID", () -> contract.RegisterEvidence(context, evidenceId, "1", HASH));
        assertRejected("INVALID_EVIDENCE_ID", () -> contract.QueryEvidence(context, evidenceId, "1"));
        verifyNoInteractions(stub);
    }

    static Stream<String> invalidEvidenceIds() {
        return Stream.of(null, "", ID.toUpperCase(Locale.ROOT), ID.replace("-", ""), " " + ID, ID + " ",
                ID.substring(1), ID + "0", "g" + ID.substring(1), "private-input\nnot-a-uuid");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "+1", "01", "0001", " 1", "1 ", "1.0", "1e2", "١",
            "2147483648", "9999999999", "21474836470"})
    void rejectsNonCanonicalOrOutOfRangeVersionsWithoutLedgerAccess(String version) {
        assertRejected("INVALID_VERSION", () -> contract.RegisterEvidence(context, ID, version, HASH));
        assertRejected("INVALID_VERSION", () -> contract.QueryEvidence(context, ID, version));
        verifyNoInteractions(stub);
    }

    @ParameterizedTest
    @MethodSource("invalidFileHashes")
    void rejectsInvalidHashesWithoutLedgerAccess(String fileHash) {
        assertRejected("INVALID_FILE_HASH", () -> contract.RegisterEvidence(context, ID, "1", fileHash));
        verifyNoInteractions(stub);
    }

    static Stream<String> invalidFileHashes() {
        return Stream.of(null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64),
                "a".repeat(63) + "z", " " + HASH, HASH + " ");
    }

    @Test
    void validationMessagesDoNotEchoUntrustedInput() {
        ChaincodeException error = assertRejected("INVALID_EVIDENCE_ID",
                () -> contract.RegisterEvidence(context, "private-input-to-hide", "1", HASH));
        assertFalse(error.getMessage().contains("private-input-to-hide"));
    }

    private JSONObject queryRecord(String evidenceId, String version) {
        JSONObject response = new JSONObject(contract.QueryEvidence(context, evidenceId, version));
        assertTrue(response.getBoolean("found"));
        return response.getJSONObject("record");
    }

    private static ChaincodeException assertRejected(String expectedCode, Runnable action) {
        ChaincodeException error = assertThrows(ChaincodeException.class, action::run);
        assertEquals(expectedCode, new String(error.getPayload(), StandardCharsets.UTF_8));
        return error;
    }
}
