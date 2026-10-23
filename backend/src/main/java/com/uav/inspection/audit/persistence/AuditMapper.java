package com.uav.inspection.audit.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** Application audit access is append-only; no update or delete operation is exposed. */
public interface AuditMapper {
    @Insert("""
            INSERT INTO audit_log
                (id, actor_type, actor_id, org_id, action, resource_type, resource_id, trace_id, result)
            VALUES
                (#{id}, 'USER', #{actorId}, #{orgId}, #{action}, 'APP_USER', #{resourceId}, #{traceId}, 'SUCCESS')
            """)
    int insertSuccess(@Param("id") String id, @Param("actorId") String actorId,
                      @Param("orgId") String orgId, @Param("action") String action,
                      @Param("resourceId") String resourceId, @Param("traceId") String traceId);

    @Insert("""
            INSERT INTO audit_log
                (id, actor_type, actor_id, org_id, action, resource_type, resource_id, trace_id, result)
            VALUES
                (#{id}, 'USER', #{actorId}, #{orgId}, #{action}, #{resourceType}, #{resourceId}, #{traceId}, 'SUCCESS')
            """)
    int insertResourceSuccess(@Param("id") String id, @Param("actorId") String actorId,
                              @Param("orgId") String orgId, @Param("action") String action,
                              @Param("resourceType") String resourceType, @Param("resourceId") String resourceId,
                              @Param("traceId") String traceId);
}
