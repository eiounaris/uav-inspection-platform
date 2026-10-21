package com.uav.inspection.blockchain;

import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.SubmitException;

import java.util.Objects;

/**
 * Split endorsement, submission and confirmation so callers can persist checkpoints
 * between network calls. RPC failures do not imply that a transaction is invalid.
 */
public interface EvidenceLedgerClient extends AutoCloseable {
    PreparedRegistration prepareRegistration(String evidenceId, int version, String fileHash)
            throws EndorseException;

    CommitCheckpoint submitPrepared(PreparedRegistration prepared) throws SubmitException;

    CommitOutcome queryCommit(CommitCheckpoint checkpoint) throws CommitStatusException;

    EvidenceQuery queryEvidence(String evidenceId, int version) throws GatewayException;

    @Override
    void close();

    record PreparedRegistration(String transactionId, byte[] transactionBytes) {
        public PreparedRegistration {
            Objects.requireNonNull(transactionId);
            transactionBytes = Objects.requireNonNull(transactionBytes).clone();
        }

        @Override
        public byte[] transactionBytes() {
            return transactionBytes.clone();
        }
    }

    record CommitCheckpoint(String transactionId, byte[] commitBytes) {
        public CommitCheckpoint {
            Objects.requireNonNull(transactionId);
            commitBytes = Objects.requireNonNull(commitBytes).clone();
        }

        @Override
        public byte[] commitBytes() {
            return commitBytes.clone();
        }
    }

    record CommitOutcome(String transactionId, boolean valid, int validationCode, long blockNumber) {}

    record EvidenceRecord(String evidenceId, int version, String fileHash,
                          String submitterMsp, String registeredTxId) {}

    record EvidenceQuery(boolean found, EvidenceRecord record) {
        public EvidenceQuery {
            if (found != (record != null)) {
                throw new IllegalArgumentException("Inconsistent evidence query response");
            }
        }
    }
}
