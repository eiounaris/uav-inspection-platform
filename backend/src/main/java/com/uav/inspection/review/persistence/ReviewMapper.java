package com.uav.inspection.review.persistence;

import com.uav.inspection.review.ReviewModels.IssueView;
import com.uav.inspection.review.ReviewModels.ReviewView;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ReviewMapper {
    @Select("SELECT task_id FROM evidence_version WHERE id=#{id}")
    String taskIdForVersion(@Param("id") String id);

    @Select("SELECT id,owner_org_id,operator_org_id,status,lock_version FROM inspection_task WHERE id=#{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);

    @Select("""
            SELECT id,task_id,subtask_id,requirement_id,execution_id,version_no,review_status,lock_version
            FROM evidence_version WHERE id=#{id} FOR UPDATE
            """)
    VersionRow lockVersion(@Param("id") String id);

    @Insert("""
            INSERT INTO issue(id,task_id,evidence_version_id,description,created_by)
            VALUES(#{id},#{taskId},#{versionId},#{description},#{createdBy})
            """)
    int insertIssue(IssueView issue);

    @Select("SELECT id,task_id,evidence_version_id AS version_id,description,created_by,created_at FROM issue WHERE id=#{id}")
    IssueView issue(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM issue WHERE evidence_version_id=#{versionId}")
    long countIssues(@Param("versionId") String versionId);

    @Select("""
            SELECT id,task_id,evidence_version_id AS version_id,description,created_by,created_at
            FROM issue WHERE evidence_version_id=#{versionId}
            ORDER BY created_at,id LIMIT #{limit} OFFSET #{offset}
            """)
    List<IssueView> issues(@Param("versionId") String versionId,@Param("limit") int limit,@Param("offset") long offset);

    @Update("""
            UPDATE evidence_version SET review_status=#{decision},lock_version=lock_version+1
            WHERE id=#{id} AND task_id=#{taskId} AND review_status='PENDING' AND lock_version=#{version}
            """)
    int decide(@Param("id") String id,@Param("taskId") String taskId,
               @Param("decision") String decision,@Param("version") long version);

    @Insert("""
            INSERT INTO review_record(id,evidence_version_id,decision,reason,reviewer_id)
            VALUES(#{id},#{versionId},#{decision},#{reason},#{reviewerId})
            """)
    int insertReview(ReviewView review);

    @Select("""
            SELECT id,evidence_version_id AS version_id,decision,reason,reviewer_id,created_at
            FROM review_record WHERE evidence_version_id=#{versionId}
            """)
    ReviewView review(@Param("versionId") String versionId);

    /** Restrict to the current execution before selecting its latest fixed material version. */
    @Select("""
            SELECT v.id FROM inspection_subtask s JOIN evidence_version v
                ON v.subtask_id=s.id AND v.execution_id=s.current_execution_id
            WHERE s.id=#{subtaskId} AND s.task_id=#{taskId}
                AND v.task_id=#{taskId} AND v.requirement_id=#{requirementId}
            ORDER BY v.version_no DESC LIMIT 1
            """)
    String currentVersionId(@Param("taskId") String taskId,@Param("subtaskId") String subtaskId,
                            @Param("requirementId") String requirementId);

    @Update("""
            UPDATE inspection_task SET status='RUNNING',lock_version=lock_version+1
            WHERE id=#{id} AND status='WAITING_ACCEPTANCE' AND lock_version=#{version}
            """)
    int reopenTask(@Param("id") String id,@Param("version") long version);

    record TaskRow(String id,String ownerOrgId,String operatorOrgId,String status,long lockVersion) {}
    record VersionRow(String id,String taskId,String subtaskId,String requirementId,String executionId,
                      int versionNo,String reviewStatus,long lockVersion) {}
}
