package com.uav.inspection.evidence.verification;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.evidence.verification.VerificationModels.*;
import com.uav.inspection.evidence.verification.persistence.VerificationMapper;
import com.uav.inspection.evidence.verification.persistence.VerificationMapper.VersionRow;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Profile("database & storage & ledger")
public class VerificationTransactions {
    private final VerificationMapper mapper;
    private final ResourcePermissionService permissions;
    private final AuditMapper audit;

    public VerificationTransactions(VerificationMapper mapper,ResourcePermissionService permissions,AuditMapper audit) {
        this.mapper=mapper; this.permissions=permissions; this.audit=audit;
    }

    @Transactional(readOnly=true)
    public Prepared prepare(CurrentUser supplied,String versionId) {
        CurrentUser actor=permissions.currentActor(supplied,false);
        VersionRow version=required(mapper.version(versionId));
        permissions.requireVersion(actor,version.id(),version.taskId(),version.ownerOrgId(),version.operatorOrgId(),false);
        return new Prepared(actor,version);
    }

    @Transactional
    public VerificationView save(CurrentUser supplied,String versionId,String source,Computed computed) {
        Prepared prepared=locked(supplied,versionId);
        String id=UUID.randomUUID().toString();
        VerificationView view=new VerificationView(id,versionId,prepared.actor().id(),source,computed.actualSha256(),
                computed.ledgerSha256(),computed.actualSize(),computed.ledgerTxId(),computed.result(),computed.reason(),null);
        one(mapper.insert(view));
        String trace=TraceContext.currentTraceId();
        if(trace==null || !trace.matches("[A-Za-z0-9_-]{1,64}")) trace=UUID.randomUUID().toString();
        one(audit.insertResourceSuccess(UUID.randomUUID().toString(),prepared.actor().id(),prepared.actor().orgId(),
                "FILE_VERIFICATION_COMPLETED","EVIDENCE_VERSION",versionId,trace));
        return mapper.record(id);
    }

    @Transactional
    public VerificationPage history(CurrentUser supplied,String versionId,int page,int pageSize) {
        locked(supplied,versionId);
        long total=mapper.count(versionId);
        List<VerificationView> records=mapper.page(versionId,pageSize,(long)(page-1)*pageSize);
        return new VerificationPage(List.copyOf(records),page,pageSize,total);
    }

    private Prepared locked(CurrentUser supplied,String versionId) {
        VersionRow located=required(mapper.version(versionId));
        if(mapper.lockTask(located.taskId())==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        CurrentUser actor=permissions.currentActor(supplied,true);
        VersionRow current=required(mapper.version(versionId));
        if(!located.taskId().equals(current.taskId())) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        permissions.requireVersion(actor,current.id(),current.taskId(),current.ownerOrgId(),current.operatorOrgId(),true);
        return new Prepared(actor,current);
    }

    private static VersionRow required(VersionRow version) {
        if(version==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        return version;
    }
    private static void one(int rows) { if(rows!=1) throw new IllegalStateException("Verification record transaction failed"); }
    public record Prepared(CurrentUser actor,VersionRow version) {}
}
