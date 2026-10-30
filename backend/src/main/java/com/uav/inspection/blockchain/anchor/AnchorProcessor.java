package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.EvidenceLedgerClient;
import com.uav.inspection.blockchain.EvidenceLedgerClient.*;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import com.uav.inspection.blockchain.anchor.AnchorTransactions.Claim;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.AttemptRow;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper;
import com.uav.inspection.common.lease.ProcessingLeaseLostException;
import com.uav.inspection.common.lease.ProcessingLeaseKeeper.Lease;
import com.uav.inspection.messaging.MessageEvent;
import com.uav.inspection.messaging.BusinessRecoveryDispatcher;
import com.uav.inspection.messaging.DurableRecovery;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.protos.peer.TxValidationCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Fabric calls occur only between committed database checkpoints. No method opens a DB transaction. */
@Service
@Profile("database & storage & messaging & ledger")
public class AnchorProcessor {
    private static final String CONFLICT_MESSAGE="chaincode response 500, A different file hash is already registered for this evidence version.";
    private static final String FORBIDDEN_MESSAGE="chaincode response 500, The calling organization is not permitted to perform this operation.";
    private final AnchorTransactions phases;
    private final InstitutionLedgerClients clients;
    private final ProcessingLeaseKeeper leases;

    @Autowired
    public AnchorProcessor(AnchorTransactions phases,InstitutionLedgerClients clients,ProcessingLeaseKeeper leases) {
        this.phases=phases; this.clients=clients; this.leases=leases;
    }

    public void process(MessageEvent event) {
        process(event,false);
        phases.verifyAcknowledgement(event);
    }

    public void recoverDue() {
        DurableRecovery.run(BusinessRecoveryDispatcher.Kind.EVIDENCE_ANCHOR,
                phases::due,event->process(event,true));
    }

    private void process(MessageEvent event,boolean recovery) {
        Claim claim=phases.claim(event,recovery);
        if(claim==null) return;
        try(Lease lease=leases.track(()->phases.renew(claim))) {
            processOwned(claim,recovery,lease);
        }
    }

    private void processOwned(Claim claim,boolean recovery,Lease lease) {
        lease.check();
        EvidenceLedgerClient ledger=clients.forOrganization(claim.submission().submitterOrgId());
        AttemptRow attempt=claim.attempt();

        // A saved confirmation request takes precedence over preparing or re-submitting anything.
        if(attempt!=null && attempt.commitBytes()!=null && !Set.of("VALID","INVALID").contains(attempt.phase())) {
            resolveCommit(claim,attempt,ledger,recovery,lease);
            return;
        }

        EvidenceQuery existing=query(claim,attempt,ledger,lease);
        if(existing==null || resolveExisting(claim,attempt,existing,lease)) return;

        if(attempt!=null && "VALID".equals(attempt.phase())) {
            checkpoint(claim,attempt,"UNKNOWN",true,"LEDGER_RECORD_NOT_VISIBLE",false,lease);
            return;
        }
        if(attempt!=null && "INVALID".equals(attempt.phase()) && !retryableInvalid(attempt.validationCode())) {
            invalid(claim,attempt,lease);
            return;
        }

        if(attempt!=null && "INVALID".equals(attempt.phase())) {
            lease.check();
            if(!phases.retryAllowed(claim)) {
                checkpoint(claim,attempt,"FAILED",true,"COMMIT_CONFLICT",false,lease);
                return;
            }
        }

        if(attempt==null || "INVALID".equals(attempt.phase())) {
            PreparedRegistration prepared;
            lease.check();
            try {
                prepared=ledger.prepareRegistration(claim.submission().evidenceId(),claim.submission().versionNo(),claim.submission().sha256());
            } catch(ProcessingLeaseLostException lost) {
                throw lost;
            } catch(GatewayException | RuntimeException failure) {
                lease.check();
                String permanent=endorsementRejection(failure);
                checkpoint(claim,null,permanent==null?"PENDING":"FAILED",permanent==null,
                        permanent==null?"PREPARE_UNAVAILABLE":permanent,false,lease);
                return;
            }
            lease.check();
            attempt=phases.prepared(claim,prepared);
            if(attempt==null) throw new ProcessingLeaseLostException();
        }

        // PREPARED/SUBMITTING/UNKNOWN without commit bytes always restores the original transaction ID.
        lease.check();
        saved(phases.submitting(claim,attempt));
        CommitCheckpoint checkpoint;
        lease.check();
        try {
            checkpoint=ledger.submitPrepared(new PreparedRegistration(attempt.txId(),attempt.transactionBytes()));
        } catch(IllegalArgumentException rejectedCheckpoint) {
            lease.check();
            checkpoint(claim,attempt,"UNKNOWN",true,"CHECKPOINT_REJECTED",true,lease);
            return;
        } catch(ProcessingLeaseLostException lost) {
            throw lost;
        } catch(GatewayException | RuntimeException uncertain) {
            lease.check();
            checkpoint(claim,attempt,"UNKNOWN",true,"SUBMIT_RESULT_UNKNOWN",true,lease);
            return;
        }
        lease.check();
        saved(phases.submitted(claim,attempt,checkpoint));
        AttemptRow submitted=withCommit(attempt,checkpoint);
        resolveCommit(claim,submitted,ledger,false,lease);
    }

    private void resolveCommit(Claim claim,AttemptRow attempt,EvidenceLedgerClient ledger,boolean recovery,Lease lease) {
        CommitOutcome result;
        lease.check();
        try {
            result=ledger.queryCommit(new CommitCheckpoint(attempt.txId(),attempt.commitBytes()));
        } catch(IllegalArgumentException rejectedCheckpoint) {
            lease.check();
            checkpoint(claim,attempt,"UNKNOWN",true,"CHECKPOINT_REJECTED",true,lease);
            return;
        } catch(ProcessingLeaseLostException lost) {
            throw lost;
        } catch(GatewayException | RuntimeException uncertain) {
            lease.check();
            // Recovery may still prove success through the immutable business record if commit RPC is unavailable.
            if(recovery) {
                EvidenceQuery existing=query(claim,attempt,ledger,lease);
                if(existing==null || resolveExisting(claim,attempt,existing,lease)) return;
            }
            checkpoint(claim,attempt,"UNKNOWN",true,"COMMIT_STATUS_UNAVAILABLE",true,lease);
            return;
        }
        lease.check();
        saved(phases.outcome(claim,attempt,result));
        AttemptRow observed=withOutcome(attempt,result);
        EvidenceQuery existing=query(claim,observed,ledger,lease);
        if(existing==null || resolveExisting(claim,observed,existing,lease)) return;
        if(result.valid()) checkpoint(claim,observed,"UNKNOWN",true,"LEDGER_RECORD_NOT_VISIBLE",false,lease);
        else invalid(claim,observed,lease);
    }

    /** Null means the unavailable query has already handed responsibility to durable recovery. */
    private EvidenceQuery query(Claim claim,AttemptRow attempt,EvidenceLedgerClient ledger,Lease lease) {
        EvidenceQuery result;
        lease.check();
        try {
            result=ledger.queryEvidence(claim.submission().evidenceId(),claim.submission().versionNo());
        } catch(ProcessingLeaseLostException lost) {
            throw lost;
        } catch(GatewayException | RuntimeException unavailable) {
            lease.check();
            boolean submitted=attempt!=null && !"PREPARED".equals(attempt.phase());
            if(!submitted && "LEDGER_FORBIDDEN".equals(endorsementRejection(unavailable))) {
                checkpoint(claim,attempt,"FAILED",false,"LEDGER_FORBIDDEN",false,lease);
            } else {
                checkpoint(claim,attempt,submitted?"UNKNOWN":"PENDING",true,"LEDGER_QUERY_UNAVAILABLE",submitted,lease);
            }
            return null;
        }
        lease.check();
        return result;
    }

    private boolean resolveExisting(Claim claim,AttemptRow attempt,EvidenceQuery existing,Lease lease) {
        lease.check();
        if(!existing.found()) return false;
        EvidenceRecord record=existing.record();
        String expectedMsp=clients.mspId(claim.submission().submitterOrgId());
        if(!claim.submission().evidenceId().equals(record.evidenceId()) || record.version()!=claim.submission().versionNo()
                || !expectedMsp.equals(record.submitterMsp()) || record.registeredTxId()==null
                || !record.registeredTxId().matches("[0-9a-f]{64}")) {
            checkpoint(claim,attempt,"FAILED",false,"LEDGER_RECORD_MISMATCH",false,lease);
        } else if(!claim.submission().sha256().equals(record.fileHash())) {
            checkpoint(claim,attempt,"FAILED",false,"EVIDENCE_HASH_CONFLICT",false,lease);
        } else {
            // A valid idempotent registration can retain a first txId different from this attempt's txId.
            lease.check();
            saved(phases.confirm(claim,record.registeredTxId(),record.submitterMsp()));
        }
        return true;
    }

    private void invalid(Claim claim,AttemptRow attempt,Lease lease) {
        if(attempt.validationCode()!=null && attempt.validationCode()==TxValidationCode.DUPLICATE_TXID_VALUE) {
            // DUPLICATE_TXID alone does not reveal whether the original occurrence was valid.
            checkpoint(claim,attempt,"UNKNOWN",true,"DUPLICATE_TX_AWAITING_RECORD",false,lease);
            return;
        }
        boolean retryable=retryableInvalid(attempt.validationCode());
        checkpoint(claim,attempt,"FAILED",retryable,retryable?"COMMIT_CONFLICT":"COMMIT_INVALID",false,lease);
    }

    private void checkpoint(Claim claim,AttemptRow attempt,String status,boolean retryable,String code,boolean unknown,Lease lease) {
        lease.check();
        saved(phases.checkpoint(claim,attempt,status,retryable,code,unknown));
    }

    private static void saved(boolean saved) {
        if(!saved) throw new ProcessingLeaseLostException();
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
