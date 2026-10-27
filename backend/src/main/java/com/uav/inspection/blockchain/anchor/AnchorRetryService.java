package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.blockchain.anchor.AnchorRetryModels.Receipt;
import com.uav.inspection.blockchain.anchor.AnchorRetryModels.Request;
import com.uav.inspection.blockchain.anchor.persistence.AnchorRetryMapper;
import com.uav.inspection.blockchain.anchor.persistence.AnchorRetryMapper.RequestRow;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.messaging.MessageEvent;
import com.uav.inspection.messaging.OutboxWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Accepts a new durable check without making any Gateway call or changing a known outcome. */
@Service
@Profile("database & storage & messaging & ledger")
public class AnchorRetryService {
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final AnchorRetryMapper mapper;
    private final ResourcePermissionService permissions;
    private final OutboxWriter outbox;
    private final AuditMapper audit;

    public AnchorRetryService(AnchorRetryMapper mapper,ResourcePermissionService permissions,
                              OutboxWriter outbox,AuditMapper audit) {
        this.mapper=mapper;this.permissions=permissions;this.outbox=outbox;this.audit=audit;
    }

    @Transactional
    public Receipt request(CurrentUser supplied,String versionId,Request request) {
        validateId(versionId);
        invalidIf(request==null);
        validateId(request.clientRequestId());
        String reason=reason(request.reason());
        String taskId=mapper.taskIdForVersion(versionId);
        if(taskId==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        var task=mapper.lockTask(taskId);
        if(task==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        CurrentUser actor=permissions.currentActor(supplied,true);
        if(!ResourcePermissionService.OPERATOR.equals(actor.orgId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        permissions.requireTask(actor,task.id(),task.ownerOrgId(),task.operatorOrgId(),Role.OPERATOR_TASK_ADMIN);
        var version=mapper.lockVersion(versionId);
        if(version==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        if(!task.id().equals(version.taskId())) throw new IllegalStateException("Anchor retry scope changed");
        var submission=mapper.lockSubmission(versionId);
        if(submission==null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        String digest=digest(versionId,reason);
        RequestRow existing=mapper.request(submission.id(),actor.id(),request.clientRequestId());
        if(existing!=null) {
            if(!digest.equals(existing.requestHash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return receipt(existing,versionId);
        }
        if("COMPLETED".equals(task.status())) throw new BusinessException(ErrorCode.TASK_COMPLETED);
        boolean recoverable=Set.of("PENDING","SUBMITTED","UNKNOWN").contains(submission.status())
                || "FAILED".equals(submission.status()) && submission.retryable();
        var dispatch=mapper.latestDispatch(submission.id());
        if(!recoverable || submission.leaseToken()!=null || submission.leaseUntil()!=null
                || dispatch==null || !"DONE".equals(dispatch.consumptionStatus()))
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        if(dispatch.dispatchNo()==Integer.MAX_VALUE) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        int dispatchNo=dispatch.dispatchNo()+1;
        int baseline=mapper.latestAttemptNo(submission.id());
        if(baseline<0) throw new IllegalStateException("Invalid anchor attempt baseline");
        if(mapper.schedule(submission.id())!=1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        String trace=TraceContext.currentTraceId();
        if(trace==null || !trace.matches("[A-Za-z0-9_-]{1,64}")) trace=UUID.randomUUID().toString();
        String eventId=outbox.enqueue(MessageEvent.EVIDENCE_ANCHOR_REQUESTED,submission.id(),trace,dispatchNo);
        String id=UUID.randomUUID().toString();
        one(mapper.insert(new RequestRow(id,submission.id(),actor.id(),request.clientRequestId(),digest,
                reason,dispatchNo,baseline,eventId,null)));
        one(audit.insertResourceSuccess(UUID.randomUUID().toString(),actor.id(),actor.orgId(),
                "EVIDENCE_ANCHOR_RECHECK_REQUESTED","CHAIN_SUBMISSION",submission.id(),trace));
        RequestRow saved=mapper.find(id);
        if(saved==null) throw new IllegalStateException("Missing anchor retry receipt");
        return receipt(saved,versionId);
    }

    private static Receipt receipt(RequestRow row,String versionId) {
        return new Receipt(row.id(),row.submissionId(),versionId,row.eventId(),row.dispatchNo(),row.createdAt());
    }

    private static String reason(String value) {
        invalidIf(value==null || value.length()>1000 || value.codePoints().allMatch(ch->
                Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch==0x85));
        return value.strip();
    }

    private static String digest(String versionId,String reason) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((versionId+"\0"+reason).getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static void validateId(String id) { invalidIf(id==null || !id.matches(UUID_PATTERN)); }
    private static void invalidIf(boolean invalid) { if(invalid) throw new BusinessException(ErrorCode.INVALID_ARGUMENT); }
    private static void one(int rows) { if(rows!=1) throw new IllegalStateException("Anchor retry transaction conflict"); }
}
