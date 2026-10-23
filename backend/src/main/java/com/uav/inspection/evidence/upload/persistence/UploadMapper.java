package com.uav.inspection.evidence.upload.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface UploadMapper {
    String SESSION_COLUMNS = """
            id, task_id, subtask_id, requirement_id, evidence_id, execution_id, submitter_org_id,
            actor_type, actor_id, client_request_id, request_hash, filename, content_type,
            expected_size, part_size, part_count, status, expires_at, lock_version, created_at, updated_at
            """;

    @Select("SELECT UTC_TIMESTAMP(3)")
    Instant databaseNow();

    @Select("SELECT id FROM evidence_version WHERE upload_session_id=#{sessionId}")
    String findVersionId(@Param("sessionId") String sessionId);

    @Select("SELECT " + SESSION_COLUMNS + " FROM upload_session WHERE id = #{id}")
    SessionRow findSession(@Param("id") String id);

    @Select("SELECT " + SESSION_COLUMNS + " FROM upload_session WHERE id = #{id} FOR UPDATE")
    SessionRow lockSession(@Param("id") String id);

    @Select("SELECT " + SESSION_COLUMNS + " FROM upload_session WHERE actor_type='USER' AND actor_id=#{actorId} AND client_request_id=#{clientRequestId}")
    SessionRow findByRequest(@Param("actorId") String actorId, @Param("clientRequestId") String clientRequestId);

    @Select("SELECT " + SESSION_COLUMNS + " FROM upload_session WHERE actor_type='USER' AND actor_id=#{actorId} AND client_request_id=#{clientRequestId} FOR UPDATE")
    SessionRow lockByRequest(@Param("actorId") String actorId, @Param("clientRequestId") String clientRequestId);

    @Insert("""
            INSERT INTO upload_session
                (id, task_id, subtask_id, requirement_id, evidence_id, execution_id, submitter_org_id,
                 actor_type, actor_id, client_request_id, request_hash, filename, content_type,
                 expected_size, part_size, part_count, status, expires_at, created_at, updated_at)
            VALUES
                (#{id}, #{taskId}, #{subtaskId}, #{requirementId}, #{evidenceId}, #{executionId}, #{submitterOrgId},
                 #{actorType}, #{actorId}, #{clientRequestId}, #{requestHash}, #{filename}, #{contentType},
                 #{expectedSize}, #{partSize}, #{partCount}, #{status}, #{expiresAt}, #{createdAt}, #{updatedAt})
            """)
    int insertSession(SessionRow session);

    @Select("SELECT id, owner_org_id, operator_org_id, status FROM inspection_task WHERE id=#{id}")
    TaskRow findTask(@Param("id") String id);

    @Select("SELECT id, owner_org_id, operator_org_id, status FROM inspection_task WHERE id=#{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);

    @Select("SELECT id, evidence_id, task_id, subtask_id FROM material_requirement WHERE id=#{id}")
    RequirementRow findRequirement(@Param("id") String id);

    @Select("SELECT id, evidence_id, task_id, subtask_id FROM material_requirement WHERE id=#{id} FOR UPDATE")
    RequirementRow lockRequirement(@Param("id") String id);

    @Select("SELECT id, task_id, current_execution_id FROM inspection_subtask WHERE id=#{id} FOR UPDATE")
    SubtaskRow lockSubtask(@Param("id") String id);

    @Select("SELECT id, subtask_id, status FROM task_execution WHERE id=#{id}")
    ExecutionRow findExecution(@Param("id") String id);

    @Select("SELECT id, subtask_id, status FROM task_execution WHERE id=#{id} FOR UPDATE")
    ExecutionRow lockExecution(@Param("id") String id);

    @Select("SELECT session_id, part_no, size, sha256, object_key FROM upload_part WHERE session_id=#{sessionId} AND part_no=#{partNo}")
    PartRow findPart(@Param("sessionId") String sessionId, @Param("partNo") int partNo);

    @Select("SELECT session_id, part_no, size, sha256, object_key FROM upload_part WHERE session_id=#{sessionId} ORDER BY part_no")
    List<PartRow> listParts(@Param("sessionId") String sessionId);

    @Insert("""
            INSERT INTO upload_part (session_id, part_no, size, sha256, object_key)
            VALUES (#{sessionId}, #{partNo}, #{size}, #{sha256}, #{objectKey})
            """)
    int insertPart(PartRow part);

    @Update("""
            UPDATE upload_session SET lock_version=lock_version+1
             WHERE id=#{id} AND lock_version=#{version} AND status='UPLOADING' AND expires_at>UTC_TIMESTAMP(3)
            """)
    int touchWritableSession(@Param("id") String id, @Param("version") long version);

    record SessionRow(String id, String taskId, String subtaskId, String requirementId, String evidenceId,
                       String executionId, String submitterOrgId, String actorType, String actorId,
                       String clientRequestId, String requestHash, String filename, String contentType,
                       long expectedSize, int partSize, int partCount, String status, Instant expiresAt,
                       long lockVersion, Instant createdAt, Instant updatedAt) {}
    record TaskRow(String id, String ownerOrgId, String operatorOrgId, String status) {}
    record RequirementRow(String id, String evidenceId, String taskId, String subtaskId) {}
    record SubtaskRow(String id, String taskId, String currentExecutionId) {}
    record ExecutionRow(String id, String subtaskId, String status) {}
    record PartRow(String sessionId, int partNo, long size, String sha256, String objectKey) {}
}
