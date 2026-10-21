package com.uav.inspection.blockchain;

import com.google.protobuf.ByteString;
import org.hyperledger.fabric.protos.common.ChannelHeader;
import org.hyperledger.fabric.protos.common.Envelope;
import org.hyperledger.fabric.protos.common.Header;
import org.hyperledger.fabric.protos.common.Payload;
import org.hyperledger.fabric.protos.common.SignatureHeader;
import org.hyperledger.fabric.protos.gateway.CommitStatusRequest;
import org.hyperledger.fabric.protos.gateway.PreparedTransaction;
import org.hyperledger.fabric.protos.gateway.SignedCommitStatusRequest;
import org.hyperledger.fabric.protos.msp.SerializedIdentity;
import org.hyperledger.fabric.protos.peer.ChaincodeHeaderExtension;
import org.hyperledger.fabric.protos.peer.ChaincodeID;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckpointValidatorTest {
    private static final String MSP_ID = "Org2MSP";
    private static final byte[] CREDENTIALS = "user-one-certificate".getBytes(StandardCharsets.UTF_8);
    private static final String CHANNEL = "mychannel";
    private static final String CHAINCODE = "evidence";
    private static final String TX_ID = "transaction-one";
    private static final String OTHER_TX_ID = "transaction-two";
    private static final byte[] MALFORMED_PROTOBUF = {(byte) 0x80};

    private final CheckpointValidator validator = new CheckpointValidator(MSP_ID, CREDENTIALS, CHANNEL, CHAINCODE);

    @Test
    void acceptsMatchingTransactionAndCommitCheckpoints() {
        assertDoesNotThrow(() -> validator.transaction(transaction(
                MSP_ID, CREDENTIALS, CHANNEL, CHAINCODE, TX_ID, TX_ID, TX_ID)));
        assertDoesNotThrow(() -> validator.commit(commit(MSP_ID, CREDENTIALS, CHANNEL, TX_ID, TX_ID)));
    }

    @Test
    void rejectsCheckpointsFromAnotherOrganization() {
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(transaction(
                "Org1MSP", CREDENTIALS, CHANNEL, CHAINCODE, TX_ID, TX_ID, TX_ID)));
        assertThrows(IllegalArgumentException.class,
                () -> validator.commit(commit("Org1MSP", CREDENTIALS, CHANNEL, TX_ID, TX_ID)));
    }

    @Test
    void rejectsAnotherCertificateEvenWithinTheSameOrganization() {
        byte[] otherCredentials = "user-two-certificate".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(transaction(
                MSP_ID, otherCredentials, CHANNEL, CHAINCODE, TX_ID, TX_ID, TX_ID)));
        assertThrows(IllegalArgumentException.class,
                () -> validator.commit(commit(MSP_ID, otherCredentials, CHANNEL, TX_ID, TX_ID)));
    }

    @Test
    void rejectsCheckpointsFromAnotherChannel() {
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(transaction(
                MSP_ID, CREDENTIALS, "another-channel", CHAINCODE, TX_ID, TX_ID, TX_ID)));
        assertThrows(IllegalArgumentException.class,
                () -> validator.commit(commit(MSP_ID, CREDENTIALS, "another-channel", TX_ID, TX_ID)));
    }

    @Test
    void rejectsTransactionForAnotherChaincode() {
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(transaction(
                MSP_ID, CREDENTIALS, CHANNEL, "another-chaincode", TX_ID, TX_ID, TX_ID)));
    }

    @Test
    void rejectsExternalTransactionIdentifierThatDiffersFromEmbeddedIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(transaction(
                MSP_ID, CREDENTIALS, CHANNEL, CHAINCODE, OTHER_TX_ID, TX_ID, TX_ID)));
        assertThrows(IllegalArgumentException.class,
                () -> validator.commit(commit(MSP_ID, CREDENTIALS, CHANNEL, OTHER_TX_ID, TX_ID)));
    }

    @Test
    void rejectsPreparedTransactionIdentifierThatDiffersFromTheRecordAndHeader() {
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(transaction(
                MSP_ID, CREDENTIALS, CHANNEL, CHAINCODE, TX_ID, OTHER_TX_ID, TX_ID)));
    }

    @Test
    void rejectsHeaderIdentifierThatDiffersFromTheRecordAndPreparedTransaction() {
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(transaction(
                MSP_ID, CREDENTIALS, CHANNEL, CHAINCODE, TX_ID, TX_ID, OTHER_TX_ID)));
    }

    @Test
    void rejectsBlankIdentifiersEvenWhenAllCopiesMatch() {
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(transaction(
                MSP_ID, CREDENTIALS, CHANNEL, CHAINCODE, " ", " ", " ")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.commit(commit(MSP_ID, CREDENTIALS, CHANNEL, " ", " ")));
    }

    @Test
    void rejectsMalformedOuterEncoding() {
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(
                new EvidenceLedgerClient.PreparedRegistration(TX_ID, MALFORMED_PROTOBUF)));
        assertThrows(IllegalArgumentException.class, () -> validator.commit(
                new EvidenceLedgerClient.CommitCheckpoint(TX_ID, MALFORMED_PROTOBUF)));
    }

    @Test
    void rejectsMalformedNestedEncoding() {
        var transaction = PreparedTransaction.newBuilder().setTransactionId(TX_ID)
                .setEnvelope(Envelope.newBuilder().setPayload(ByteString.copyFrom(MALFORMED_PROTOBUF)))
                .build();
        var commit = SignedCommitStatusRequest.newBuilder()
                .setRequest(ByteString.copyFrom(MALFORMED_PROTOBUF)).build();
        assertThrows(IllegalArgumentException.class, () -> validator.transaction(
                new EvidenceLedgerClient.PreparedRegistration(TX_ID, transaction.toByteArray())));
        assertThrows(IllegalArgumentException.class, () -> validator.commit(
                new EvidenceLedgerClient.CommitCheckpoint(TX_ID, commit.toByteArray())));
    }

    @Test
    void snapshotsConfiguredCredentialsSoCallerMutationCannotChangeTheAcceptedIdentity() {
        byte[] suppliedCredentials = CREDENTIALS.clone();
        var isolated = new CheckpointValidator(MSP_ID, suppliedCredentials, CHANNEL, CHAINCODE);
        suppliedCredentials[0] ^= 1;

        assertDoesNotThrow(() -> isolated.transaction(transaction(
                MSP_ID, CREDENTIALS, CHANNEL, CHAINCODE, TX_ID, TX_ID, TX_ID)));
        assertDoesNotThrow(() -> isolated.commit(commit(MSP_ID, CREDENTIALS, CHANNEL, TX_ID, TX_ID)));
        assertThrows(IllegalArgumentException.class, () -> isolated.transaction(transaction(
                MSP_ID, suppliedCredentials, CHANNEL, CHAINCODE, TX_ID, TX_ID, TX_ID)));
        assertThrows(IllegalArgumentException.class,
                () -> isolated.commit(commit(MSP_ID, suppliedCredentials, CHANNEL, TX_ID, TX_ID)));
    }

    private static EvidenceLedgerClient.PreparedRegistration transaction(
            String mspId, byte[] credentials, String channel, String chaincode,
            String externalId, String preparedId, String headerId) {
        var extension = ChaincodeHeaderExtension.newBuilder()
                .setChaincodeId(ChaincodeID.newBuilder().setName(chaincode)).build();
        var channelHeader = ChannelHeader.newBuilder().setChannelId(channel).setTxId(headerId)
                .setExtension(extension.toByteString()).build();
        var signatureHeader = SignatureHeader.newBuilder()
                .setCreator(identity(mspId, credentials).toByteString()).build();
        var header = Header.newBuilder().setChannelHeader(channelHeader.toByteString())
                .setSignatureHeader(signatureHeader.toByteString()).build();
        var payload = Payload.newBuilder().setHeader(header).build();
        var prepared = PreparedTransaction.newBuilder().setTransactionId(preparedId)
                .setEnvelope(Envelope.newBuilder().setPayload(payload.toByteString())).build();
        return new EvidenceLedgerClient.PreparedRegistration(externalId, prepared.toByteArray());
    }

    private static EvidenceLedgerClient.CommitCheckpoint commit(
            String mspId, byte[] credentials, String channel, String externalId, String embeddedId) {
        var request = CommitStatusRequest.newBuilder().setChannelId(channel).setTransactionId(embeddedId)
                .setIdentity(identity(mspId, credentials).toByteString()).build();
        var signed = SignedCommitStatusRequest.newBuilder().setRequest(request.toByteString()).build();
        return new EvidenceLedgerClient.CommitCheckpoint(externalId, signed.toByteArray());
    }

    private static SerializedIdentity identity(String mspId, byte[] credentials) {
        return SerializedIdentity.newBuilder().setMspid(mspId)
                .setIdBytes(ByteString.copyFrom(credentials)).build();
    }
}
