package com.uav.inspection.inspection.persistence;

import com.uav.inspection.inspection.TaskModels.ExecutionView;
import com.uav.inspection.inspection.TaskModels.ExecutionHistoryView;
import com.uav.inspection.inspection.TaskModels.SubtaskProgress;
import com.uav.inspection.inspection.TaskModels.RequirementView;
import com.uav.inspection.inspection.TaskModels.TaskSummary;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface TaskMapper {
    String TASK_COLUMNS = "id, owner_org_id, operator_org_id, name, scope, status, created_by, lock_version, created_at, updated_at, completed_at";
    String REQUIREMENT_COLUMNS = "id, evidence_id, task_id, subtask_id, name, required, next_version_no, lock_version";
    String SUBTASK_COLUMNS = "id, task_id, name, current_execution_id, lock_version";
    String EXECUTION_COLUMNS = "id, subtask_id, drone_id, status, progress, started_at, closed_at, lock_version";
    String READ_SCOPE = """
             FROM inspection_task t JOIN task_member m ON m.task_id = t.id
            WHERE m.user_id = #{userId}
              AND ((t.owner_org_id = #{orgId} AND #{ownerRead} = 1)
                OR (t.operator_org_id = #{orgId} AND #{operatorRead} = 1))
            """;

    @Select("SELECT COUNT(*) " + READ_SCOPE)
    long countVisible(@Param("userId") String userId, @Param("orgId") String orgId,
                      @Param("ownerRead") boolean ownerRead, @Param("operatorRead") boolean operatorRead);

    @Select("""
            SELECT t.id, t.owner_org_id, t.operator_org_id, t.name, t.scope, t.status, t.created_by,
                   t.lock_version, t.created_at, t.updated_at, t.completed_at
            """ + READ_SCOPE + " ORDER BY t.created_at DESC, t.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<TaskSummary> listVisible(@Param("userId") String userId, @Param("orgId") String orgId,
                                 @Param("ownerRead") boolean ownerRead, @Param("operatorRead") boolean operatorRead,
                                 @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT " + TASK_COLUMNS + " FROM inspection_task WHERE id = #{id}")
    TaskSummary findTask(@Param("id") String id);

    @Select("SELECT " + TASK_COLUMNS + " FROM inspection_task WHERE id = #{id} FOR UPDATE")
    TaskSummary lockTask(@Param("id") String id);

    @Insert("""
            INSERT INTO inspection_task (id, owner_org_id, operator_org_id, name, scope, status, created_by)
            VALUES (#{id}, #{ownerOrgId}, #{operatorOrgId}, #{name}, #{scope}, 'DRAFT', #{createdBy})
            """)
    int insertTask(@Param("id") String id, @Param("ownerOrgId") String ownerOrgId,
                   @Param("operatorOrgId") String operatorOrgId, @Param("name") String name,
                   @Param("scope") String scope, @Param("createdBy") String createdBy);

    @Update("UPDATE inspection_task SET lock_version = lock_version + 1 WHERE id = #{id} AND lock_version = #{version}")
    int touchTask(@Param("id") String id, @Param("version") long version);

    @Update("""
            UPDATE inspection_task SET status = 'ASSIGNED', lock_version = lock_version + 1
             WHERE id = #{id} AND lock_version = #{version} AND status = 'DRAFT'
            """)
    int assignTask(@Param("id") String id, @Param("version") long version);

    @Update("""
            UPDATE inspection_task SET status = 'RUNNING', lock_version = lock_version + 1
             WHERE id = #{id} AND lock_version = #{version} AND status = 'ASSIGNED'
            """)
    int startTask(@Param("id") String id, @Param("version") long version);

    @Update("""
            UPDATE inspection_task SET status='WAITING_ACCEPTANCE',lock_version=lock_version+1
            WHERE id=#{id} AND status='RUNNING' AND lock_version=#{version}
            """)
    int submitAcceptance(@Param("id") String id,@Param("version") long version);

    @Update("""
            UPDATE inspection_task SET status='COMPLETED',completed_at=UTC_TIMESTAMP(3),lock_version=lock_version+1
            WHERE id=#{id} AND status='WAITING_ACCEPTANCE' AND lock_version=#{version}
            """)
    int completeTask(@Param("id") String id,@Param("version") long version);

    @Select("""
            SELECT u.id AS user_id, u.org_id, u.username, CAST(u.roles AS CHAR) AS roles_json
              FROM task_member m JOIN app_user u ON u.id = m.user_id
             WHERE m.task_id = #{taskId} ORDER BY u.org_id, u.id
            """)
    List<MemberRow> listMembers(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO task_member (task_id, user_id)
            SELECT #{taskId}, #{userId} WHERE NOT EXISTS
                (SELECT 1 FROM task_member WHERE task_id = #{taskId} AND user_id = #{userId})
            """)
    int addMember(@Param("taskId") String taskId, @Param("userId") String userId);

    @Delete("""
            DELETE m FROM task_member m JOIN app_user u ON u.id = m.user_id
             WHERE m.task_id = #{taskId} AND u.org_id = #{orgId} AND m.user_id <> #{creatorId}
            """)
    int deleteManagedMembers(@Param("taskId") String taskId, @Param("orgId") String orgId,
                             @Param("creatorId") String creatorId);

    @Select("SELECT id, org_id, username, CAST(roles AS CHAR) AS roles_json, enabled FROM app_user WHERE id = #{id}")
    CandidateRow findCandidate(@Param("id") String id);

    @Select("SELECT " + REQUIREMENT_COLUMNS + " FROM material_requirement WHERE task_id = #{taskId} ORDER BY id")
    List<RequirementView> listRequirements(@Param("taskId") String taskId);

    @Select("SELECT " + REQUIREMENT_COLUMNS + " FROM material_requirement WHERE task_id = #{taskId} ORDER BY id FOR UPDATE")
    List<RequirementView> lockRequirements(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO material_requirement (id, evidence_id, task_id, name, required)
            VALUES (#{id}, #{evidenceId}, #{taskId}, #{name}, #{required})
            """)
    int insertRequirement(@Param("id") String id, @Param("evidenceId") String evidenceId,
                          @Param("taskId") String taskId, @Param("name") String name,
                          @Param("required") boolean required);

    @Update("""
            UPDATE material_requirement SET name = #{name}, required = #{required}, lock_version = lock_version + 1
             WHERE id = #{id} AND task_id = #{taskId} AND lock_version = #{version}
            """)
    int updateRequirement(@Param("id") String id, @Param("taskId") String taskId,
                          @Param("name") String name, @Param("required") boolean required,
                          @Param("version") long version);

    @Delete("DELETE FROM material_requirement WHERE id = #{id} AND task_id = #{taskId} AND subtask_id IS NULL")
    int deleteUnboundRequirement(@Param("id") String id, @Param("taskId") String taskId);

    @Update("""
            UPDATE material_requirement SET subtask_id = #{subtaskId}, lock_version = lock_version + 1
             WHERE id = #{id} AND task_id = #{taskId} AND subtask_id IS NULL AND lock_version = #{version}
            """)
    int bindRequirement(@Param("id") String id, @Param("taskId") String taskId,
                        @Param("subtaskId") String subtaskId, @Param("version") long version);

    @Select("SELECT " + SUBTASK_COLUMNS + " FROM inspection_subtask WHERE task_id = #{taskId} ORDER BY id")
    List<SubtaskRow> listSubtasks(@Param("taskId") String taskId);

    @Select("SELECT " + SUBTASK_COLUMNS + " FROM inspection_subtask WHERE task_id = #{taskId} ORDER BY id FOR UPDATE")
    List<SubtaskRow> lockSubtasks(@Param("taskId") String taskId);

    @Select("SELECT " + SUBTASK_COLUMNS + " FROM inspection_subtask WHERE id = #{id}")
    SubtaskRow findSubtask(@Param("id") String id);

    @Select("SELECT " + SUBTASK_COLUMNS + " FROM inspection_subtask WHERE id = #{id} FOR UPDATE")
    SubtaskRow lockSubtask(@Param("id") String id);

    @Insert("INSERT INTO inspection_subtask (id, task_id, name) VALUES (#{id}, #{taskId}, #{name})")
    int insertSubtask(@Param("id") String id, @Param("taskId") String taskId, @Param("name") String name);

    @Update("""
            UPDATE inspection_subtask SET current_execution_id = #{executionId}, lock_version = lock_version + 1
             WHERE id = #{id} AND current_execution_id IS NULL AND lock_version = #{version}
            """)
    int setInitialExecution(@Param("id") String id, @Param("executionId") String executionId,
                            @Param("version") long version);

    @Update("UPDATE inspection_subtask SET lock_version = lock_version + 1 WHERE id = #{id} AND lock_version = #{version}")
    int touchSubtask(@Param("id") String id, @Param("version") long version);

    @Select("SELECT id, operator_org_id, enabled FROM drone WHERE id = #{id}")
    DeviceRow findDevice(@Param("id") String id);

    @Select("SELECT id, operator_org_id, enabled FROM drone WHERE id = #{id} FOR UPDATE")
    DeviceRow lockDevice(@Param("id") String id);

    @Select("SELECT " + EXECUTION_COLUMNS + " FROM task_execution WHERE id = #{id}")
    ExecutionView findExecution(@Param("id") String id);

    @Select("SELECT " + EXECUTION_COLUMNS + " FROM task_execution WHERE subtask_id = #{subtaskId} ORDER BY id FOR UPDATE")
    List<ExecutionView> lockExecutions(@Param("subtaskId") String subtaskId);

    @Insert("INSERT INTO task_execution (id, subtask_id, drone_id, status) VALUES (#{id}, #{subtaskId}, #{droneId}, 'READY')")
    int insertExecution(@Param("id") String id, @Param("subtaskId") String subtaskId, @Param("droneId") String droneId);

    @Update("""
            UPDATE task_execution SET status = 'RUNNING', started_at = #{startedAt}, lock_version = lock_version + 1
             WHERE id = #{id} AND status = 'READY' AND lock_version = #{version}
            """)
    int startExecution(@Param("id") String id, @Param("version") long version, @Param("startedAt") Instant startedAt);

    @Select("SELECT UTC_TIMESTAMP(3)")
    Instant databaseNow();

    @Update("""
            UPDATE task_execution SET status='CLOSED',closed_at=#{closedAt},lock_version=lock_version+1
            WHERE id=#{id} AND status='RUNNING' AND lock_version=#{version}
            """)
    int closeExecution(@Param("id") String id,@Param("version") long version,@Param("closedAt") Instant closedAt);

    @Insert("""
            INSERT INTO task_execution(id,subtask_id,drone_id,status,progress,started_at)
            VALUES(#{id},#{subtaskId},#{droneId},'RUNNING',0,#{startedAt})
            """)
    int insertRunningExecution(@Param("id") String id,@Param("subtaskId") String subtaskId,
                               @Param("droneId") String droneId,@Param("startedAt") Instant startedAt);

    @Update("""
            UPDATE inspection_subtask SET current_execution_id=#{newExecutionId},lock_version=lock_version+1
            WHERE id=#{id} AND current_execution_id=#{previousExecutionId} AND lock_version=#{version}
            """)
    int replaceCurrentExecution(@Param("id") String id,@Param("previousExecutionId") String previousExecutionId,
                                @Param("newExecutionId") String newExecutionId,@Param("version") long version);

    @Update("""
            UPDATE inspection_task SET status='RUNNING',lock_version=lock_version+1
            WHERE id=#{id} AND lock_version=#{version} AND status IN ('RUNNING','WAITING_ACCEPTANCE')
            """)
    int touchAdjustedTask(@Param("id") String id,@Param("version") long version);

    @Insert("""
            INSERT INTO execution_adjustment(id,task_id,subtask_id,previous_execution_id,new_execution_id,actor_id,reason,created_at)
            VALUES(#{id},#{taskId},#{subtaskId},#{previousExecutionId},#{newExecutionId},#{actorId},#{reason},#{createdAt})
            """)
    int insertAdjustment(AdjustmentRow adjustment);

    @Select("SELECT COUNT(*) FROM task_execution WHERE subtask_id=#{subtaskId}")
    long countExecutions(@Param("subtaskId") String subtaskId);

    @Select("""
            SELECT e.id,e.subtask_id,e.drone_id,e.status,e.progress,e.started_at,e.closed_at,e.lock_version,
                   COALESCE(e.id=s.current_execution_id,FALSE) AS `current`,a.previous_execution_id,a.actor_id AS adjusted_by,
                   a.reason AS adjustment_reason,a.created_at AS adjusted_at
            FROM task_execution e JOIN inspection_subtask s ON s.id=e.subtask_id
                LEFT JOIN execution_adjustment a ON a.new_execution_id=e.id
            WHERE e.subtask_id=#{subtaskId} ORDER BY e.created_at DESC,e.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<ExecutionHistoryView> pageExecutions(@Param("subtaskId") String subtaskId,@Param("size") int size,@Param("offset") long offset);

    @Select("""
            SELECT s.id AS subtask_id,s.name,s.current_execution_id,e.drone_id,e.status AS execution_status,
                   COALESCE(e.progress,0) AS progress,
                   (SELECT COUNT(*) FROM task_execution h WHERE h.subtask_id=s.id AND (s.current_execution_id IS NULL OR h.id<>s.current_execution_id)) AS historical_execution_count
            FROM inspection_subtask s LEFT JOIN task_execution e ON e.id=s.current_execution_id
            WHERE s.task_id=#{taskId} ORDER BY s.id
            """)
    List<SubtaskProgress> subtaskProgress(@Param("taskId") String taskId);

    @Select("""
            SELECT r.id AS requirement_id,r.evidence_id,r.name,r.required,r.subtask_id,s.current_execution_id,
                   v.id AS version_id,v.version_no,v.review_status,c.status AS anchor_status,
                   (SELECT COUNT(*) FROM evidence_version h WHERE h.requirement_id=r.id
                       AND (s.current_execution_id IS NULL OR h.execution_id<>s.current_execution_id)) AS historical_version_count
            FROM material_requirement r LEFT JOIN inspection_subtask s ON s.id=r.subtask_id
                LEFT JOIN evidence_version v ON v.requirement_id=r.id AND v.execution_id=s.current_execution_id
                    AND v.version_no=(SELECT MAX(latest.version_no) FROM evidence_version latest
                        WHERE latest.requirement_id=r.id AND latest.execution_id=s.current_execution_id)
                LEFT JOIN chain_submission c ON c.version_id=v.id
            WHERE r.task_id=#{taskId} ORDER BY r.id
            """)
    List<RequirementProgressRow> requirementProgress(@Param("taskId") String taskId);

    @Select("""
            SELECT COUNT(*) FROM upload_session u JOIN inspection_subtask s
                ON s.id=u.subtask_id AND s.task_id=u.task_id AND u.execution_id=s.current_execution_id
            WHERE u.task_id=#{taskId} AND u.status='VERIFYING'
            """)
    long countCurrentVerifyingUploads(@Param("taskId") String taskId);

    record MemberRow(String userId, String orgId, String username, String rolesJson) {}
    record CandidateRow(String id, String orgId, String username, String rolesJson, boolean enabled) {}
    record SubtaskRow(String id, String taskId, String name, String currentExecutionId, long lockVersion) {}
    record DeviceRow(String id, String operatorOrgId, boolean enabled) {}
    record AdjustmentRow(String id,String taskId,String subtaskId,String previousExecutionId,String newExecutionId,
                         String actorId,String reason,Instant createdAt) {}
    record RequirementProgressRow(String requirementId,String evidenceId,String name,boolean required,String subtaskId,
                                   String currentExecutionId,String versionId,Integer versionNo,String reviewStatus,
                                   String anchorStatus,long historicalVersionCount) {}
}
