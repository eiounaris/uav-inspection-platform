package com.uav.inspection.evidence.finalization;

import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper;
import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper.FixedVersion;
import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper.WorkState;
import com.uav.inspection.evidence.upload.persistence.UploadMapper;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.*;
import com.uav.inspection.messaging.MessageEvent;
import com.uav.inspection.messaging.InvalidMessageException;
import com.uav.inspection.messaging.OutboxWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Every method is a short database transaction. The processor owns all external I/O. */
@Service
@Profile("database & storage & messaging")
public class FinalizationTransactions {
    private final UploadMapper uploads;
    private final FinalizationMapper work;
    private final OutboxWriter outbox;

    public FinalizationTransactions(UploadMapper uploads, FinalizationMapper work, OutboxWriter outbox) {
        this.uploads=uploads; this.work=work; this.outbox=outbox;
    }

    @Transactional(readOnly=true)
    public List<MessageEvent> due() { return work.due(); }

    @Transactional
    public Claim claim(MessageEvent event, boolean recovery) {
        MessageEvent original=work.findEvent(event.eventId(),event.bizId());
        if (original==null || !original.equals(event)) throw new InvalidMessageException();
        Locked locked=lock(event.bizId());
        if (locked==null) { work.done(event.eventId()); return null; }
        if (!"VERIFYING".equals(locked.session().status())) { work.done(event.eventId()); return null; }
        if (!recovery && "DONE".equals(work.consumption(event.eventId()))) return null;
        String token=UUID.randomUUID().toString();
        if (work.claim(event.bizId(),token)!=1) return null;
        work.processing(event.eventId());
        return new Claim(event,locked.session(),List.copyOf(uploads.listParts(event.bizId())),token);
    }

    @Transactional
    public boolean finish(Claim claim,String objectKey,long size,String digest) {
        Locked locked=owned(claim);
        if (locked==null) return false;
        if (!Set.of("RUNNING","WAITING_ACCEPTANCE").contains(locked.task().status())) {
            return checkpointLocked(claim,locked,true,"TASK_NOT_ACTIVE");
        }
        SessionRow session=locked.session();
        if (uploads.findVersionId(session.id())!=null) {
            one(work.complete(session.id(),claim.token())); work.done(claim.event().eventId()); return true;
        }
        if (!objectKey.equals("evidence-files/"+session.id()+"/"+claim.token()) || size!=session.expectedSize()
                || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid final object result");
        int number=work.lockVersionNumber(session.requirementId());
        if (number<1 || number==Integer.MAX_VALUE) throw new IllegalStateException("Evidence version exhausted");
        String versionId=UUID.randomUUID().toString();
        one(work.insertVersion(new FixedVersion(versionId,session.requirementId(),session.evidenceId(),session.taskId(),
                session.subtaskId(),session.executionId(),session.id(),number,
                "USER".equals(session.actorType())?session.actorId():null,
                "DEVICE".equals(session.actorType())?session.actorId():null,session.submitterOrgId(),objectKey,size,digest)));
        one(work.advanceVersion(session.requirementId(),number));
        String submissionId=UUID.randomUUID().toString();
        one(work.insertSubmission(submissionId,versionId));
        outbox.enqueue("EVIDENCE_ANCHOR_REQUESTED",submissionId,claim.event().traceId());
        one(work.complete(session.id(),claim.token()));
        if (session.executionId().equals(locked.subtask().currentExecutionId())) work.reopenTask(session.taskId());
        appendAudit(claim,"UPLOAD_FINALIZED");
        work.done(claim.event().eventId());
        return true;
    }

    @Transactional
    public boolean checkpoint(Claim claim,boolean permanent,String error) {
        Locked locked=owned(claim);
        return locked!=null && checkpointLocked(claim,locked,permanent,error);
    }

    private boolean checkpointLocked(Claim claim,Locked locked,boolean permanent,String error) {
        WorkState state=work.state(claim.session().id());
        int[] backoff={2,10,30,60,300};
        Instant next=permanent?null:uploads.databaseNow().plusSeconds(backoff[Math.min(state.retryCount(),backoff.length-1)]);
        one(work.checkpoint(claim.session().id(),claim.token(),permanent?"FAILED":"VERIFYING",next,error));
        work.done(claim.event().eventId());
        appendAudit(claim,permanent?"UPLOAD_FINALIZATION_FAILED":"UPLOAD_FINALIZATION_RETRY_SCHEDULED");
        return true;
    }

    private Locked owned(Claim claim) {
        Locked locked=lock(claim.session().id());
        if (locked==null || !"VERIFYING".equals(locked.session().status())) return null;
        WorkState state=work.state(claim.session().id());
        if (state==null || !claim.token().equals(state.leaseToken()) || state.leaseUntil()==null
                || !state.leaseUntil().isAfter(uploads.databaseNow())) return null;
        return locked;
    }

    private Locked lock(String id) {
        SessionRow seed=uploads.findSession(id);
        if (seed==null) return null;
        TaskRow task=uploads.lockTask(seed.taskId());
        SubtaskRow subtask=uploads.lockSubtask(seed.subtaskId());
        RequirementRow requirement=uploads.lockRequirement(seed.requirementId());
        ExecutionRow execution=uploads.lockExecution(seed.executionId());
        SessionRow session=uploads.lockSession(id);
        if (task==null || subtask==null || requirement==null || execution==null || session==null)
            throw new IllegalStateException("Missing finalization scope");
        return new Locked(task,subtask,session);
    }

    private void appendAudit(Claim claim,String action) {
        one(work.audit(UUID.randomUUID().toString(),claim.session().submitterOrgId(),action,claim.session().id(),claim.event().traceId()));
    }

    private static void one(int count) {
        if (count!=1) throw new IllegalStateException("Finalization checkpoint conflict");
    }

    public record Claim(MessageEvent event,SessionRow session,List<PartRow> parts,String token) {}
    private record Locked(TaskRow task,SubtaskRow subtask,SessionRow session) {}
}
