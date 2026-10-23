package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.EvidenceLedgerClient.CommitCheckpoint;
import com.uav.inspection.blockchain.EvidenceLedgerClient.CommitOutcome;
import com.uav.inspection.blockchain.EvidenceLedgerClient.PreparedRegistration;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.AttemptRow;
import com.uav.inspection.blockchain.anchor.persistence.AnchorMapper.SubmissionRow;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.messaging.InvalidMessageException;
import com.uav.inspection.messaging.MessageEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Database-only phases. Returning from these methods also means their transaction committed. */
@Service
@Profile("database & storage & messaging & ledger")
public class AnchorTransactions {
    private final AnchorMapper mapper;

    public AnchorTransactions(AnchorMapper mapper) { this.mapper=mapper; }

    @Transactional(readOnly=true)
    public List<MessageEvent> due() { return mapper.due(); }

    @Transactional
    public Claim claim(MessageEvent event,boolean recovery) {
        MessageEvent original=mapper.event(event.eventId(),event.bizId());
        if(original==null || !original.equals(event)) throw new InvalidMessageException();
        SubmissionRow submission=lock(event.bizId());
        if(submission==null) { mapper.consume(event.eventId(),"DONE"); return null; }
        if(!ResourcePermissionService.OPERATOR.equals(submission.submitterOrgId())) throw new InvalidMessageException();
        if("CONFIRMED".equals(submission.status()) || "FAILED".equals(submission.status())
                && (!submission.retryable() || submission.retryCount()>=10)) {
            mapper.consume(event.eventId(),"DONE"); return null;
        }
        if(!recovery && "DONE".equals(mapper.consumption(event.eventId()))) return null;
        String token=UUID.randomUUID().toString();
        if(mapper.claim(submission.id(),token)!=1) return null;
        mapper.consume(event.eventId(),"PROCESSING");
        return new Claim(event,submission,mapper.latest(submission.id()),token);
    }

    @Transactional
    public AttemptRow prepared(Claim claim,PreparedRegistration prepared) {
        if(owned(claim)==null) return null;
        if(!prepared.transactionId().matches("[0-9a-f]{64}") || prepared.transactionBytes().length==0)
            throw new IllegalArgumentException("Invalid prepared transaction checkpoint");
        AttemptRow previous=mapper.latest(claim.submission().id());
        if(previous!=null && !"INVALID".equals(previous.phase()))
            throw new IllegalStateException("Existing transaction must be reconciled first");
        int number=previous==null?1:Math.addExact(previous.attemptNo(),1);
        String id=UUID.randomUUID().toString();
        one(mapper.insertPrepared(id,claim.submission().id(),number,prepared.transactionId(),prepared.transactionBytes(),claim.token()));
        return mapper.attempt(id);
    }

    @Transactional
    public boolean submitting(Claim claim,AttemptRow attempt) {
        if(owned(claim)==null) return false;
        positive(mapper.submitting(claim.submission().id(),attempt.id(),claim.token()));
        return true;
    }

    @Transactional
    public boolean submitted(Claim claim,AttemptRow attempt,CommitCheckpoint checkpoint) {
        if(owned(claim)==null) return false;
        if(!attempt.txId().equals(checkpoint.transactionId()) || checkpoint.commitBytes().length==0)
            throw new IllegalArgumentException("Commit checkpoint does not match prepared transaction");
        // MySQL reports both affected tables for this joined update.
        positive(mapper.submitted(claim.submission().id(),attempt.id(),claim.token(),checkpoint.transactionId(),checkpoint.commitBytes()));
        return true;
    }

    @Transactional
    public boolean outcome(Claim claim,AttemptRow attempt,CommitOutcome result) {
        if(owned(claim)==null) return false;
        if(!attempt.txId().equals(result.transactionId()) || result.validationCode()<0 || result.blockNumber()<0
                || result.valid()!=(result.validationCode()==0)) throw new IllegalArgumentException("Invalid commit outcome");
        positive(mapper.outcome(claim.submission().id(),attempt.id(),claim.token(),result.transactionId(),
                result.valid()?"VALID":"INVALID",result.validationCode(),result.blockNumber()));
        return true;
    }

    @Transactional
    public boolean confirm(Claim claim,String firstTransactionId,String msp) {
        if(owned(claim)==null) return false;
        if(firstTransactionId==null || !firstTransactionId.matches("[0-9a-f]{64}") || msp==null || msp.isBlank())
            throw new IllegalArgumentException("Invalid ledger confirmation");
        one(mapper.confirm(claim.submission().id(),claim.token(),firstTransactionId,msp));
        done(claim,"EVIDENCE_ANCHOR_CONFIRMED");
        return true;
    }

    @Transactional
    public boolean checkpoint(Claim claim,AttemptRow attempt,String status,boolean retryable,String code,boolean unknown) {
        SubmissionRow submission=owned(claim);
        if(submission==null) return false;
        if(!List.of("PENDING","UNKNOWN","FAILED").contains(status) || code==null || !code.matches("[A-Z][A-Z0-9_]{0,199}"))
            throw new IllegalArgumentException("Invalid anchor recovery checkpoint");
        if(attempt!=null) positive(mapper.attemptError(submission.id(),attempt.id(),claim.token(),unknown,code));
        int[] backoff={2,10,30,60,300};
        boolean continues=!"FAILED".equals(status) || retryable && submission.retryCount()+1<10;
        Instant next=continues?mapper.now().plusSeconds(backoff[Math.min(submission.retryCount(),backoff.length-1)]):null;
        one(mapper.checkpoint(submission.id(),claim.token(),status,retryable,next,code));
        done(claim,"FAILED".equals(status)?"EVIDENCE_ANCHOR_FAILED":"EVIDENCE_ANCHOR_RETRY_SCHEDULED");
        return true;
    }

    private SubmissionRow owned(Claim claim) {
        SubmissionRow current=lock(claim.submission().id());
        if(current==null || !claim.token().equals(current.leaseToken()) || current.leaseUntil()==null
                || !current.leaseUntil().isAfter(mapper.now())) return null;
        return current;
    }

    private SubmissionRow lock(String id) {
        SubmissionRow seed=mapper.find(id);
        if(seed==null) return null;
        if(mapper.lockTask(seed.taskId())==null) throw new IllegalStateException("Missing anchor task scope");
        return mapper.lock(id);
    }

    private void done(Claim claim,String action) {
        mapper.consume(claim.event().eventId(),"DONE");
        one(mapper.audit(UUID.randomUUID().toString(),claim.submission().submitterOrgId(),action,
                claim.submission().id(),claim.event().traceId()));
    }

    private static void one(int rows) { if(rows!=1) throw new LostLeaseException(); }
    private static void positive(int rows) { if(rows<1) throw new LostLeaseException(); }

    public record Claim(MessageEvent event,SubmissionRow submission,AttemptRow attempt,String token) {}
    public static final class LostLeaseException extends RuntimeException {
        LostLeaseException() { super("Anchor processing lease lost before checkpoint"); }
    }
}
