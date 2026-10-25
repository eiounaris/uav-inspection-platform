package com.uav.inspection.sharing;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.sharing.SharingModels.*;
import com.uav.inspection.sharing.persistence.SharingMapper;
import com.uav.inspection.sharing.persistence.SharingMapper.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Profile("database")
public class SharingService {
    private static final Instant MAX_DATABASE_TIME=Instant.parse("9999-12-31T23:59:59.999Z");
    private static final Instant MIN_DATABASE_TIME=Instant.parse("1000-01-01T00:00:00Z");
    private final SharingMapper mapper;
    private final ResourcePermissionService permissions;
    private final AuditMapper audit;

    public SharingService(SharingMapper mapper,ResourcePermissionService permissions,AuditMapper audit) {
        this.mapper=mapper; this.permissions=permissions; this.audit=audit;
    }

    @Transactional
    public GrantView apply(CurrentUser supplied,ApplyRequest request) {
        invalidIf(request==null);
        id(request.clientRequestId()); id(request.versionId());
        String reason=text(request.reason(),false);
        Instant requested=instant(request.requestedValidUntil());
        String hash=requestHash(request.versionId(),reason,requested);
        if(supplied==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        // Locate an original request first so changed-content retries cannot acquire a different task's grant lock.
        GrantRow original=mapper.request(supplied.id(),request.clientRequestId());
        VersionScope located=required(mapper.version(original==null?request.versionId():original.versionId()));
        TaskRow task=required(mapper.lockTask(located.taskId()));
        CurrentUser actor=registered(permissions.currentActor(supplied,true));
        // The current account lock also serializes this applicant's request key across different tasks.
        GrantRow repeated=mapper.request(actor.id(),request.clientRequestId());
        if(repeated!=null) return repeated(repeated,hash,request.versionId());
        VersionScope version=required(mapper.version(request.versionId()));
        if(!task.id().equals(version.taskId())) throw new IllegalStateException("Access request scope changed");
        invalidIf(!requested.isAfter(mapper.databaseNow()));
        String grantId=UUID.randomUUID().toString();
        GrantRow row=new GrantRow(grantId,version.id(),actor.id(),request.clientRequestId(),hash,"PENDING",reason,requested,
                null,null,null,null,null,null,null,0,null,null);
        try {
            if(mapper.insert(row)!=1) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        } catch(DuplicateKeyException duplicate) {
            GrantRow existing=mapper.request(actor.id(),request.clientRequestId());
            if(existing==null) throw duplicate;
            return repeated(existing,hash,request.versionId());
        }
        appendAudit(actor,"ACCESS_GRANT_REQUESTED",grantId);
        return view(required(mapper.grant(grantId)),mapper.databaseNow());
    }

    @Transactional
    public GrantView decide(CurrentUser supplied,String grantId,DecisionRequest request) {
        id(grantId);
        invalidIf(request==null || !("APPROVED".equals(request.decision()) || "REJECTED".equals(request.decision())));
        boolean approve="APPROVED".equals(request.decision());
        String reason=text(request.reason(),approve);
        Instant until=approve?instant(request.validUntil()):null;
        invalidIf(!approve && request.validUntil()!=null);
        Managed managed=managed(supplied,grantId);
        GrantRow grant=managed.grant();
        expected(grant,request.expectedLockVersion());
        conflictIf(!"PENDING".equals(grant.status()));
        if(approve) {
            Instant now=mapper.databaseNow();
            invalidIf(!until.isAfter(now) || until.isAfter(grant.requestedValidUntil()));
            VersionScope version=required(mapper.version(grant.versionId()));
            conflictIf(!"APPROVED".equals(version.reviewStatus()) || !"CONFIRMED".equals(version.anchorStatus()));
        }
        changed(mapper.decide(grant.id(),grant.lockVersion(),request.decision(),managed.actor().id(),reason,until));
        appendAudit(managed.actor(),approve?"ACCESS_GRANT_APPROVED":"ACCESS_GRANT_REJECTED",grant.id());
        return view(required(mapper.grant(grant.id())),mapper.databaseNow());
    }

    @Transactional
    public GrantView revoke(CurrentUser supplied,String grantId,RevokeRequest request) {
        id(grantId); invalidIf(request==null);
        String reason=text(request.reason(),false);
        Managed managed=managed(supplied,grantId);
        GrantRow grant=managed.grant();
        expected(grant,request.expectedLockVersion());
        conflictIf(!"APPROVED".equals(grant.status()) || grant.validUntil()==null || !grant.validUntil().isAfter(mapper.databaseNow()));
        changed(mapper.revoke(grant.id(),grant.lockVersion(),managed.actor().id(),reason));
        appendAudit(managed.actor(),"ACCESS_GRANT_REVOKED",grant.id());
        return view(required(mapper.grant(grant.id())),mapper.databaseNow());
    }

    @Transactional
    public GrantPage list(CurrentUser supplied,Boolean mine,String taskId,int page,int pageSize) {
        invalidIf(page<1 || pageSize<1 || pageSize>100);
        boolean own=Boolean.TRUE.equals(mine);
        invalidIf(own && taskId!=null || !own && taskId==null);
        long total; List<GrantRow> rows;
        long offset=((long)page-1)*pageSize;
        if(own) {
            CurrentUser actor=registered(permissions.currentActor(supplied,true));
            total=mapper.countMine(actor.id()); rows=mapper.mine(actor.id(),pageSize,offset);
        } else {
            id(taskId);
            TaskRow task=required(mapper.lockTask(taskId));
            approver(supplied,task);
            total=mapper.countTask(task.id()); rows=mapper.task(task.id(),pageSize,offset);
        }
        Instant now=mapper.databaseNow();
        return new GrantPage(rows.stream().map(row->view(row,now)).toList(),page,pageSize,total);
    }

    private GrantView repeated(GrantRow row,String hash,String versionId) {
        if(!row.requestHash().equals(hash) || !row.versionId().equals(versionId)) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        return view(required(mapper.lockGrant(row.id())),mapper.databaseNow());
    }

    private Managed managed(CurrentUser supplied,String grantId) {
        GrantRow located=required(mapper.grant(grantId));
        VersionScope version=required(mapper.version(located.versionId()));
        TaskRow task=required(mapper.lockTask(version.taskId()));
        CurrentUser actor=approver(supplied,task);
        GrantRow grant=required(mapper.lockGrant(grantId));
        if(!located.versionId().equals(grant.versionId())) throw new IllegalStateException("Access grant scope changed");
        return new Managed(actor,grant);
    }

    private CurrentUser approver(CurrentUser supplied,TaskRow task) {
        CurrentUser actor=permissions.currentActor(supplied,true);
        if(!ResourcePermissionService.OWNER.equals(actor.orgId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        permissions.requireTask(actor,task.id(),task.ownerOrgId(),task.operatorOrgId(),Role.OWNER_DATA_APPROVER);
        return actor;
    }

    private static CurrentUser registered(CurrentUser actor) {
        if(!ResourcePermissionService.OWNER.equals(actor.orgId()) && !ResourcePermissionService.OPERATOR.equals(actor.orgId()))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        return actor;
    }

    private void appendAudit(CurrentUser actor,String action,String grantId) {
        String trace=TraceContext.currentTraceId();
        if(trace==null || !trace.matches("[A-Za-z0-9_-]{1,64}")) trace=UUID.randomUUID().toString();
        if(audit.insertResourceSuccess(UUID.randomUUID().toString(),actor.id(),actor.orgId(),action,"ACCESS_GRANT",grantId,trace)!=1)
            throw new IllegalStateException("Access grant audit failed");
    }

    private static GrantView view(GrantRow row,Instant now) {
        String status="APPROVED".equals(row.status()) && !row.validUntil().isAfter(now)?"EXPIRED":row.status();
        return new GrantView(row.id(),row.versionId(),row.applicantId(),row.clientRequestId(),status,row.reason(),row.requestedValidUntil(),
                row.approverId(),row.decisionReason(),row.decidedAt(),row.validUntil(),row.revokedBy(),row.revokeReason(),row.revokedAt(),
                row.lockVersion(),row.createdAt(),row.updatedAt());
    }

    private static void expected(GrantRow row,Long version) {
        invalidIf(version==null || version<0); conflictIf(row.lockVersion()!=version);
    }
    private static String text(String value,boolean optional) {
        if(value==null && optional) return null;
        invalidIf(value==null || value.length()>1000 || value.codePoints().allMatch(ch->Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch==0x85));
        return value.strip();
    }
    private static Instant instant(Instant value) {
        invalidIf(value==null || value.isBefore(MIN_DATABASE_TIME) || value.isAfter(MAX_DATABASE_TIME));
        return value.truncatedTo(ChronoUnit.MILLIS);
    }
    private static String requestHash(String versionId,String reason,Instant until) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String value:List.of(versionId,reason,until.toString())) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void id(String id) {
        invalidIf(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
    private static <T> T required(T row) { if(row==null) throw new BusinessException(ErrorCode.FORBIDDEN); return row; }
    private static void changed(int rows) { conflictIf(rows!=1); }
    private static void invalidIf(boolean condition) { if(condition) throw new BusinessException(ErrorCode.INVALID_ARGUMENT); }
    private static void conflictIf(boolean condition) { if(condition) throw new BusinessException(ErrorCode.VERSION_CONFLICT); }
    private record Managed(CurrentUser actor,GrantRow grant) {}
}
