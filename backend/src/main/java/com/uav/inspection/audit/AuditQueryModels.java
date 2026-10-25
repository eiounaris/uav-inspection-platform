package com.uav.inspection.audit;

import java.time.Instant;
import java.util.List;

public final class AuditQueryModels {
    private AuditQueryModels() {}

    public record AuditView(String id,String actorType,String actorId,String orgId,String action,
                            String resourceType,String resourceId,String traceId,String result,Instant createdAt,
                            String taskId,String versionId,String subtaskId,String executionId,String actionLabel) {}

    public record AuditPage(List<AuditView> items,int page,int pageSize,long total) {}
}
