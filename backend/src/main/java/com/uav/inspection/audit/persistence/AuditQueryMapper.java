package com.uav.inspection.audit.persistence;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

public interface AuditQueryMapper {
    String FIELDS="a.id,a.actor_type,a.actor_id,a.org_id,a.action,a.resource_type,a.resource_id,a.trace_id,a.result,a.created_at,";

    // Each branch is disjoint by resource_type, and every join uses a unique identifier.
    // Device identity, actor organization and trace IDs never establish task ownership.
    String SCOPED="SELECT "+FIELDS+"""
            a.resource_id AS task_id,NULL AS version_id,NULL AS subtask_id,NULL AS execution_id
            FROM audit_log a WHERE a.resource_type='INSPECTION_TASK' AND a.resource_id=#{taskId}
            UNION ALL
            SELECT
            """+FIELDS+"""
            s.task_id,NULL,e.subtask_id,e.id
            FROM audit_log a JOIN task_execution e ON a.resource_type='TASK_EXECUTION' AND a.resource_id=e.id
                JOIN inspection_subtask s ON s.id=e.subtask_id WHERE s.task_id=#{taskId}
            UNION ALL
            SELECT
            """+FIELDS+"""
            s.task_id,NULL,e.subtask_id,e.id
            FROM audit_log a JOIN device_event d ON a.resource_type='DEVICE_EVENT' AND a.resource_id=d.id
                JOIN task_execution e ON e.id=d.execution_id JOIN inspection_subtask s ON s.id=e.subtask_id
            WHERE s.task_id=#{taskId}
            UNION ALL
            SELECT
            """+FIELDS+"""
            u.task_id,v.id,u.subtask_id,u.execution_id
            FROM audit_log a JOIN upload_session u ON a.resource_type='UPLOAD_SESSION' AND a.resource_id=u.id
                LEFT JOIN evidence_version v ON v.upload_session_id=u.id WHERE u.task_id=#{taskId}
            UNION ALL
            SELECT
            """+FIELDS+"""
            u.task_id,v.id,u.subtask_id,u.execution_id
            FROM audit_log a JOIN storage_cleanup_task c ON a.resource_type='CLEANUP_TASK' AND a.resource_id=c.id
                JOIN upload_session u ON u.id=c.upload_id
                LEFT JOIN evidence_version v ON v.upload_session_id=u.id WHERE u.task_id=#{taskId}
            UNION ALL
            SELECT
            """+FIELDS+"""
            v.task_id,v.id,v.subtask_id,v.execution_id
            FROM audit_log a JOIN evidence_version v ON a.resource_type='EVIDENCE_VERSION' AND a.resource_id=v.id
            WHERE v.task_id=#{taskId}
            UNION ALL
            SELECT
            """+FIELDS+"""
            v.task_id,v.id,v.subtask_id,v.execution_id
            FROM audit_log a JOIN chain_submission c ON a.resource_type='CHAIN_SUBMISSION' AND a.resource_id=c.id
                JOIN evidence_version v ON v.id=c.version_id WHERE v.task_id=#{taskId}
            UNION ALL
            SELECT
            """+FIELDS+"""
            i.task_id,v.id,v.subtask_id,v.execution_id
            FROM audit_log a JOIN issue i ON a.resource_type='ISSUE' AND a.resource_id=i.id
                JOIN evidence_version v ON v.id=i.evidence_version_id AND v.task_id=i.task_id WHERE i.task_id=#{taskId}
            UNION ALL
            SELECT
            """+FIELDS+"""
            v.task_id,v.id,v.subtask_id,v.execution_id
            FROM audit_log a JOIN access_grant g ON a.resource_type='ACCESS_GRANT' AND a.resource_id=g.id
                JOIN evidence_version v ON v.id=g.version_id WHERE v.task_id=#{taskId}
            """;
    String FILTERS="""
            <where>
                <if test='action != null'>AND scoped.action=#{action}</if>
                <if test='resourceType != null'>AND scoped.resource_type=#{resourceType}</if>
                <if test='traceId != null'>AND scoped.trace_id=#{traceId}</if>
            </where>
            """;

    @Select("SELECT id,owner_org_id,operator_org_id FROM inspection_task WHERE id=#{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);

    @Select("<script>SELECT COUNT(*) FROM ("+SCOPED+") scoped "+FILTERS+"</script>")
    long count(@Param("taskId") String taskId,@Param("action") String action,
               @Param("resourceType") String resourceType,@Param("traceId") String traceId);

    @Select("""
            <script>SELECT scoped.id,scoped.actor_type,scoped.actor_id,scoped.org_id,scoped.action,
                scoped.resource_type,scoped.resource_id,scoped.trace_id,scoped.result,scoped.created_at,
                scoped.task_id,scoped.version_id,scoped.subtask_id,scoped.execution_id FROM (
            """+SCOPED+") scoped "+FILTERS+"""
             ORDER BY scoped.created_at DESC,scoped.id DESC LIMIT #{size} OFFSET #{offset}</script>
            """)
    List<AuditRow> page(@Param("taskId") String taskId,@Param("action") String action,
                       @Param("resourceType") String resourceType,@Param("traceId") String traceId,
                       @Param("size") int size,@Param("offset") long offset);

    record TaskRow(String id,String ownerOrgId,String operatorOrgId) {}
    record AuditRow(String id,String actorType,String actorId,String orgId,String action,String resourceType,String resourceId,
                    String traceId,String result,Instant createdAt,String taskId,String versionId,String subtaskId,String executionId) {}
}
