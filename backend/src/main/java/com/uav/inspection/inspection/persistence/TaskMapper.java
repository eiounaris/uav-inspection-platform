package com.uav.inspection.inspection.persistence;

import com.uav.inspection.inspection.TaskModels.ExecutionView;
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
    String TASK_COLUMNS = "id, owner_org_id, operator_org_id, name, scope, status, created_by, lock_version, created_at, updated_at";
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
                   t.lock_version, t.created_at, t.updated_at
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

    record MemberRow(String userId, String orgId, String username, String rolesJson) {}
    record CandidateRow(String id, String orgId, String username, String rolesJson, boolean enabled) {}
    record SubtaskRow(String id, String taskId, String name, String currentExecutionId, long lockVersion) {}
    record DeviceRow(String id, String operatorOrgId, boolean enabled) {}
}
