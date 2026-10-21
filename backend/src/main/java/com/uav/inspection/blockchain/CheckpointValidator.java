package com.uav.inspection.blockchain;

import com.google.protobuf.InvalidProtocolBufferException;
import org.hyperledger.fabric.protos.common.ChannelHeader;
import org.hyperledger.fabric.protos.common.Payload;
import org.hyperledger.fabric.protos.common.SignatureHeader;
import org.hyperledger.fabric.protos.gateway.CommitStatusRequest;
import org.hyperledger.fabric.protos.gateway.PreparedTransaction;
import org.hyperledger.fabric.protos.gateway.SignedCommitStatusRequest;
import org.hyperledger.fabric.protos.msp.SerializedIdentity;
import org.hyperledger.fabric.protos.peer.ChaincodeHeaderExtension;

import java.util.Arrays;
import java.util.Objects;

/** Checks that persisted Gateway checkpoints belong to the configured identity and ledger scope. */
final class CheckpointValidator {
    private final String mspId;
    private final byte[] credentials;
    private final String channel;
    private final String chaincode;

    CheckpointValidator(String mspId, byte[] credentials, String channel, String chaincode) {
        this.mspId = Objects.requireNonNull(mspId);
        this.credentials = Objects.requireNonNull(credentials).clone();
        this.channel = Objects.requireNonNull(channel);
        this.chaincode = Objects.requireNonNull(chaincode);
    }

    void transaction(EvidenceLedgerClient.PreparedRegistration checkpoint) {
        try {
            var prepared = PreparedTransaction.parseFrom(checkpoint.transactionBytes());
            var payload = Payload.parseFrom(prepared.getEnvelope().getPayload());
            var header = ChannelHeader.parseFrom(payload.getHeader().getChannelHeader());
            var creator = SignatureHeader.parseFrom(payload.getHeader().getSignatureHeader()).getCreator();
            var extension = ChaincodeHeaderExtension.parseFrom(header.getExtension());
            identity(SerializedIdentity.parseFrom(creator));
            if (!channel.equals(header.getChannelId())
                    || !chaincode.equals(extension.getChaincodeId().getName())
                    || checkpoint.transactionId().isBlank()
                    || !checkpoint.transactionId().equals(prepared.getTransactionId())
                    || !checkpoint.transactionId().equals(header.getTxId())) {
                throw new IllegalArgumentException("Transaction checkpoint scope or identifier mismatch");
            }
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid transaction checkpoint encoding", exception);
        }
    }

    void commit(EvidenceLedgerClient.CommitCheckpoint checkpoint) {
        try {
            var signed = SignedCommitStatusRequest.parseFrom(checkpoint.commitBytes());
            var request = CommitStatusRequest.parseFrom(signed.getRequest());
            identity(SerializedIdentity.parseFrom(request.getIdentity()));
            if (!channel.equals(request.getChannelId()) || checkpoint.transactionId().isBlank()
                    || !checkpoint.transactionId().equals(request.getTransactionId())) {
                throw new IllegalArgumentException("Commit checkpoint scope or identifier mismatch");
            }
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid commit checkpoint encoding", exception);
        }
    }

    private void identity(SerializedIdentity creator) {
        if (!mspId.equals(creator.getMspid())
                || !Arrays.equals(credentials, creator.getIdBytes().toByteArray())) {
            throw new IllegalArgumentException("Checkpoint must be restored with its original signing identity");
        }
    }
}
