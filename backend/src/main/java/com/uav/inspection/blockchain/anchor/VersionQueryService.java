package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.anchor.AnchorViews.AnchorView;
import com.uav.inspection.blockchain.anchor.AnchorViews.VersionView;
import com.uav.inspection.blockchain.anchor.AnchorViews.VersionPage;
import com.uav.inspection.blockchain.anchor.persistence.AnchorQueryMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile("database")
public class VersionQueryService {
    private final AnchorQueryMapper mapper;
    private final ResourcePermissionService permissions;

    public VersionQueryService(AnchorQueryMapper mapper, ResourcePermissionService permissions) {
        this.mapper = mapper;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public VersionView version(CurrentUser supplied, String id) {
        var row = visible(supplied, id);
        return new VersionView(row.id(), row.evidenceId(), row.versionNo(), row.requirementId(), row.taskId(),
                row.subtaskId(), row.executionId(), row.filename(), row.contentType(), row.size(), row.sha256(),
                row.reviewStatus(), row.createdAt(), anchorRow(id));
    }

    @Transactional(readOnly = true)
    public AnchorView anchor(CurrentUser supplied, String id) {
        visible(supplied, id);
        return anchorRow(id);
    }

    @Transactional(readOnly = true)
    public VersionPage list(CurrentUser supplied, String taskId, String requirementId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (taskId != null) validateId(taskId);
        if (requirementId != null) validateId(requirementId);
        CurrentUser actor = permissions.currentActor(supplied, false);
        if (!(ResourcePermissionService.OWNER.equals(actor.orgId())
                || ResourcePermissionService.OPERATOR.equals(actor.orgId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        var now = mapper.now();
        boolean taskRole = permissions.canReadTasks(actor);
        long total = mapper.countVisible(actor.id(), actor.orgId(), taskRole, taskId, requirementId, now);
        var items = mapper.pageVisible(actor.id(), actor.orgId(), taskRole, taskId, requirementId,
                now, pageSize, (long) (page - 1) * pageSize);
        return new VersionPage(List.copyOf(items), page, pageSize, total);
    }

    private AnchorQueryMapper.VersionRow visible(CurrentUser supplied, String id) {
        validateId(id);
        CurrentUser actor = permissions.currentActor(supplied, false);
        var row = mapper.version(id);
        if (row == null) throw new BusinessException(ErrorCode.FORBIDDEN);
        permissions.requireVersion(actor, row.id(), row.taskId(), row.ownerOrgId(), row.operatorOrgId(), false);
        return row;
    }

    private AnchorView anchorRow(String id) {
        var result = mapper.anchor(id);
        if (result == null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        return result;
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
