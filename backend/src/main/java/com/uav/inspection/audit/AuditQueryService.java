package com.uav.inspection.audit;

import com.uav.inspection.audit.AuditQueryModels.AuditPage;
import com.uav.inspection.audit.AuditQueryModels.AuditView;
import com.uav.inspection.audit.persistence.AuditQueryMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("database")
public class AuditQueryService {
    private final AuditQueryMapper mapper;
    private final ResourcePermissionService permissions;

    public AuditQueryService(AuditQueryMapper mapper,ResourcePermissionService permissions) {
        this.mapper=mapper; this.permissions=permissions;
    }

    @Transactional
    public AuditPage page(CurrentUser supplied,String taskId,int page,int pageSize,String action,String resourceType,String traceId) {
        invalidIf(taskId==null || !taskId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        invalidIf(page<1 || pageSize<1 || pageSize>100);
        code(action); code(resourceType);
        invalidIf(traceId!=null && !traceId.matches("[A-Za-z0-9_-]{1,64}"));
        var task=mapper.lockTask(taskId);
        if(task==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        CurrentUser actor=permissions.currentActor(supplied,true);
        permissions.requireTaskHistory(actor,task.id(),task.ownerOrgId(),task.operatorOrgId());
        long total=mapper.count(taskId,action,resourceType,traceId);
        var rows=mapper.page(taskId,action,resourceType,traceId,pageSize,((long)page-1)*pageSize);
        var items=rows.stream().map(row->new AuditView(row.id(),row.actorType(),row.actorId(),row.orgId(),row.action(),
                row.resourceType(),row.resourceId(),row.traceId(),row.result(),row.createdAt(),row.taskId(),row.versionId(),
                row.subtaskId(),row.executionId(),"URL_ISSUED".equals(row.action())?"下载地址签发":row.action())).toList();
        return new AuditPage(items,page,pageSize,total);
    }

    private static void code(String value) { invalidIf(value!=null && !value.matches("[A-Z][A-Z0-9_]{0,63}")); }
    private static void invalidIf(boolean condition) { if(condition) throw new BusinessException(ErrorCode.INVALID_ARGUMENT); }
}
