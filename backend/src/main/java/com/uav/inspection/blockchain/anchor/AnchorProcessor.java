package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.EvidenceLedgerClient.*;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import com.uav.inspection.blockchain.anchor.AnchorTransactions.Claim;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.AttemptRow;
import com.uav.inspection.messaging.MessageEvent;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.protos.peer.TxValidationCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Fabric calls occur only between committed database checkpoints. No method opens a DB transaction. */
@Service
@Profile("database & storage & messaging & ledger")
public class AnchorProcessor {
    private static final Logger LOG=LoggerFactory.getLogger(AnchorProcessor.class);
    private static final String CONFLICT_MESSAGE="chaincode response 500, A different file hash is already registered for this evidence version.";
    private static final String FORBIDDEN_MESSAGE="chaincode response 500, The calling organization is not permitted to perform this operation.";
    private final AnchorTransactions phases;
    private final InstitutionLedgerClients clients;

    public AnchorProcessor(AnchorTransactions phases,InstitutionLedgerClients clients) {
        this.phases=phases; this.clients=clients;
    }

    public void process(MessageEvent event) { process(event,false); }

    public void recoverDue() {
        for(MessageEvent event:phases.due()) {
            try { process(event,true); }
            catch(RuntimeException failure) { LOG.warn("Anchor recovery deferred: {}",failure.getClass().getSimpleName()); }
        }
    }

    private void process(MessageEvent event,boolean recovery) {
        Claim claim=phases.claim(event,recovery);
        if(claim==null) return;
        EvidenceLedgerClient ledger=clients.forOrganization(claim.submission().submitterOrgId());
        AttemptRow attempt=claim.attempt();

        // A saved confirmation request takes precedence over preparing or re-submitting anything.
        if(attempt!=null && attempt.commitBytes()!=null && !Set.of("VALID","INVALID").contains(attempt.phase())) {
            resolveCommit(claim,attempt,ledger,recovery);
            return;
        }

        EvidenceQuery existing=query(claim,attempt,ledger);
        if(existing==null || resolveExisting(claim,attempt,existing)) return;

        if(attempt!=null && "VALID".equals(attempt.phase())) {
            phases.checkpoint(claim,attempt,"UNKNOWN",true,"LEDGER_RECORD_NOT_VISIBLE",false);
            return;
        }
        if(attempt!=null && "INVALID".equals(attempt.phase()) && !retryableInvalid(attempt.validationCode())) {
            invalid(claim,attempt);
            return;
        }

        if(attempt==null || "INVALID".equals(attempt.phase())) {
            PreparedRegistration prepared;
            try {
                prepared=ledger.prepareRegistration(claim.submission().evidenceId(),claim.submission().versionNo(),claim.submission().sha256());
            } catch(GatewayException | RuntimeException failure) {
                String permanent=endorsementRejection(failure);
                phases.checkpoint(claim,null,permanent==null?"PENDING":"FAILED",permanent==null,
                        permanent==null?"PREPARE_UNAVAILABLE":permanent,false);
                return;
            }
            attempt=phases.prepared(claim,prepared);
            if(attempt==null) return;
        }

        // PREPARED/SUBMITTING/UNKNOWN without commit bytes always restores the original transaction ID.
        if(!phases.submitting(claim,attempt)) return;
        CommitCheckpoint checkpoint;
        try {
            checkpoint=ledger.submitPrepared(new PreparedRegistration(attempt.txId(),attempt.transactionBytes()));
        } catch(IllegalArgumentException rejectedCheckpoint) {
            phases.checkpoint(claim,attempt,"UNKNOWN",true,"CHECKPOINT_REJECTED",true);
            return;
        } catch(GatewayException | RuntimeException uncertain) {
            phases.checkpoint(claim,attempt,"UNKNOWN",true,"SUBMIT_RESULT_UNKNOWN",true);
            return;
        }
        if(!phases.submitted(claim,attempt,checkpoint)) return;
        AttemptRow submitted=withCommit(attempt,checkpoint);
        resolveCommit(claim,submitted,ledger,false);
    }

    private void resolveCommit(Claim claim,AttemptRow attempt,EvidenceLedgerClient ledger,boolean recovery) {
        CommitOutcome result;
        try {
            result=ledger.queryCommit(new CommitCheckpoint(attempt.txId(),attempt.commitBytes()));
        } catch(IllegalArgumentException rejectedCheckpoint) {
            phases.checkpoint(claim,attempt,"UNKNOWN",true,"CHECKPOINT_REJECTED",true);
            return;
        } catch(GatewayException | RuntimeException uncertain) {
            // Recovery may still prove success through the immutable business record if commit RPC is unavailable.
            if(recovery) {
                EvidenceQuery existing=query(claim,attempt,ledger);
                if(existing==null || resolveExisting(claim,attempt,existing)) return;
            }
            phases.checkpoint(claim,attempt,"UNKNOWN",true,"COMMIT_STATUS_UNAVAILABLE",true);
            return;
        }
        if(!phases.outcome(claim,attempt,result)) return;
        AttemptRow observed=withOutcome(attempt,result);
        EvidenceQuery existing=query(claim,observed,ledger);
        if(existing==null || resolveExisting(claim,observed,existing)) return;
        if(result.valid()) phases.checkpoint(claim,observed,"UNKNOWN",true,"LEDGER_RECORD_NOT_VISIBLE",false);
        else invalid(claim,observed);
    }

    /** Null means the unavailable query has already handed responsibility to durable recovery. */
    private EvidenceQuery query(Claim claim,AttemptRow attempt,EvidenceLedgerClient ledger) {
        try {
            return ledger.queryEvidence(claim.submission().evidenceId(),claim.submission().versionNo());
        } catch(GatewayException | RuntimeException unavailable) {
            boolean submitted=attempt!=null && !"PREPARED".equals(attempt.phase());
            phases.checkpoint(claim,attempt,submitted?"UNKNOWN":"PENDING",true,"LEDGER_QUERY_UNAVAILABLE",submitted);
            return null;
        }
    }

    private boolean resolveExisting(Claim claim,AttemptRow attempt,EvidenceQuery existing) {
        if(!existing.found()) return false;
        EvidenceRecord record=existing.record();
        String expectedMsp=clients.mspId(claim.submission().submitterOrgId());
        if(!claim.submission().evidenceId().equals(record.evidenceId()) || record.version()!=claim.submission().versionNo()
                || !expectedMsp.equals(record.submitterMsp()) || record.registeredTxId()==null
                || !record.registeredTxId().matches("[0-9a-f]{64}")) {
            phases.checkpoint(claim,attempt,"FAILED",false,"LEDGER_RECORD_MISMATCH",false);
        } else if(!claim.submission().sha256().equals(record.fileHash())) {
            phases.checkpoint(claim,attempt,"FAILED",false,"EVIDENCE_HASH_CONFLICT",false);
        } else {
            // A valid idempotent registration can retain a first txId different from this attempt's txId.
            phases.confirm(claim,record.registeredTxId(),record.submitterMsp());
        }
        return true;
    }

    private void invalid(Claim claim,AttemptRow attempt) {
        if(attempt.validationCode()!=null && attempt.validationCode()==TxValidationCode.DUPLICATE_TXID_VALUE) {
            // DUPLICATE_TXID alone does not reveal whether the original occurrence was valid.
            phases.checkpoint(claim,attempt,"UNKNOWN",true,"DUPLICATE_TX_AWAITING_RECORD",false);
            return;
        }
        boolean retryable=retryableInvalid(attempt.validationCode());
        phases.checkpoint(claim,attempt,"FAILED",retryable,retryable?"COMMIT_CONFLICT":"COMMIT_INVALID",false);
    }

    private static boolean retryableInvalid(Integer code) {
        return code!=null && (code==TxValidationCode.MVCC_READ_CONFLICT_VALUE || code==TxValidationCode.PHANTOM_READ_CONFLICT_VALUE);
    }

    private static String endorsementRejection(Throwable failure) {
        if(failure instanceof GatewayException gateway) {
            if(gateway.getStatus().getCode()==Status.Code.PERMISSION_DENIED
                    || gateway.getStatus().getCode()==Status.Code.UNAUTHENTICATED) return "LEDGER_FORBIDDEN";
            if(failure instanceof EndorseException && gateway.getStatus().getCode()==Status.Code.ABORTED) {
                for(var detail:gateway.getDetails()) {
                    if(!Set.of("Org1MSP","Org2MSP").contains(detail.getMspId()) || detail.getAddress().isBlank()) continue;
                    if(CONFLICT_MESSAGE.equals(detail.getMessage())) return "EVIDENCE_HASH_CONFLICT";
                    if(FORBIDDEN_MESSAGE.equals(detail.getMessage())) return "LEDGER_FORBIDDEN";
                }
            }
        }
        if(failure instanceof StatusRuntimeException grpc && (grpc.getStatus().getCode()==Status.Code.PERMISSION_DENIED
                || grpc.getStatus().getCode()==Status.Code.UNAUTHENTICATED)) return "LEDGER_FORBIDDEN";
        return null;
    }

    private static AttemptRow withCommit(AttemptRow attempt,CommitCheckpoint checkpoint) {
        return new AttemptRow(attempt.id(),attempt.submissionId(),attempt.attemptNo(),attempt.txId(),"SUBMITTED",attempt.transactionBytes(),
                checkpoint.commitBytes(),attempt.validationCode(),attempt.blockNumber(),null,attempt.createdAt(),attempt.updatedAt());
    }

    private static AttemptRow withOutcome(AttemptRow attempt,CommitOutcome outcome) {
        return new AttemptRow(attempt.id(),attempt.submissionId(),attempt.attemptNo(),attempt.txId(),outcome.valid()?"VALID":"INVALID",
                attempt.transactionBytes(),attempt.commitBytes(),outcome.validationCode(),outcome.blockNumber(),null,attempt.createdAt(),attempt.updatedAt());
    }
}
