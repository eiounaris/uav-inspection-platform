package com.uav.inspection.sharing;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.evidence.storage.DownloadUrlSigner;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.sharing.persistence.DownloadMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Holds task/account/grant locks through local signing and append-only issuance audit; no network I/O. */
@Service
@Profile("database & storage")
public class DownloadService {
    private final DownloadMapper mapper;
    private final ResourcePermissionService permissions;
    private final DownloadUrlSigner signer;
    private final AuditMapper audit;

    public DownloadService(DownloadMapper mapper,ResourcePermissionService permissions,DownloadUrlSigner signer,AuditMapper audit) {
        this.mapper=mapper; this.permissions=permissions; this.signer=signer; this.audit=audit;
    }

    @Transactional
    public DownloadUrl issue(CurrentUser supplied,String versionId) {
        if(versionId==null || !versionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        var version=mapper.version(versionId);
        if(version==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        var task=mapper.lockTask(version.taskId());
        if(task==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        CurrentUser actor=permissions.currentActor(supplied,true);
        var access=permissions.requireVersion(actor,versionId,task.id(),task.ownerOrgId(),task.operatorOrgId(),true);
        Instant now=mapper.now();
        long ttl=access.taskPath()?300:Math.min(300,Duration.between(now,access.validUntil()).getSeconds());
        if(ttl<1) throw new BusinessException(ErrorCode.FORBIDDEN);
        Instant signingAt=now.truncatedTo(ChronoUnit.SECONDS);
        var signed=signer.sign(version.objectKey(),signingAt,ttl);
        Instant lastNow=mapper.now();
        if(signed==null || signed.url()==null || signed.url().isBlank() || signed.expiresAt()==null
                || !signed.expiresAt().equals(signingAt.plusSeconds(ttl)))
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        if(!signed.expiresAt().isAfter(lastNow)
                || !access.taskPath() && signed.expiresAt().isAfter(access.validUntil()))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        String trace=TraceContext.currentTraceId();
        if(trace==null || !trace.matches("[A-Za-z0-9_-]{1,64}")) trace=UUID.randomUUID().toString();
        if(audit.insertResourceSuccess(UUID.randomUUID().toString(),actor.id(),actor.orgId(),"URL_ISSUED",
                "EVIDENCE_VERSION",versionId,trace)!=1) throw new IllegalStateException("Download issuance audit failed");
        return new DownloadUrl(signed.url(),signed.expiresAt());
    }

    public record DownloadUrl(String url,Instant expiresAt) {
        @Override public String toString() { return "DownloadUrl[expiresAt="+expiresAt+"]"; }
    }
}
