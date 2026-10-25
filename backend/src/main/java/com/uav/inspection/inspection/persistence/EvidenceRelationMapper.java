package com.uav.inspection.inspection.persistence;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** Page first, then aggregate each one-to-many relation independently for at most 100 page IDs. */
public interface EvidenceRelationMapper {
    String FILTERS="""
            WHERE v.task_id=#{taskId}
            <if test='requirementId!=null'>AND v.requirement_id=#{requirementId}</if>
            <if test='executionId!=null'>AND v.execution_id=#{executionId}</if>
            """;
    String PAGE_IDS="""
            <foreach collection='versionIds' item='versionId' open='(' separator=',' close=')'>#{versionId}</foreach>
            """;

    @Select("SELECT id,owner_org_id,operator_org_id FROM inspection_task WHERE id=#{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);

    @Select("SELECT UTC_TIMESTAMP(3)")
    Instant now();

    @Select("<script>SELECT COUNT(*) FROM evidence_version v "+FILTERS+"</script>")
    long countVersions(@Param("taskId") String taskId,@Param("requirementId") String requirementId,@Param("executionId") String executionId);

    @Select("""
            <script>
            SELECT v.id AS version_id,v.requirement_id,v.evidence_id,v.version_no,v.subtask_id,v.execution_id,
                   v.upload_session_id AS upload_id,u.filename,u.content_type,v.size,v.sha256,v.created_at,
                   COALESCE(v.execution_id=s.current_execution_id,FALSE) AS current_execution,
                   NOT EXISTS(SELECT 1 FROM evidence_version newer WHERE newer.requirement_id=v.requirement_id
                       AND newer.execution_id=v.execution_id AND newer.version_no>v.version_no) AS latest_in_execution
            FROM evidence_version v JOIN upload_session u ON u.id=v.upload_session_id
                JOIN inspection_subtask s ON s.id=v.subtask_id AND s.task_id=v.task_id
            """+FILTERS+" ORDER BY v.created_at DESC,v.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<VersionRow> versions(@Param("taskId") String taskId,@Param("requirementId") String requirementId,
                              @Param("executionId") String executionId,@Param("limit") int limit,@Param("offset") long offset);

    @Select("<script>SELECT evidence_version_id AS version_id,COUNT(*) AS total FROM issue WHERE evidence_version_id IN "+PAGE_IDS+
            " GROUP BY evidence_version_id</script>")
    List<CountRow> issues(@Param("versionIds") List<String> versionIds);

    @Select("<script>SELECT evidence_version_id AS version_id,id AS review_record_id,decision,reviewer_id,created_at AS reviewed_at "+
            "FROM review_record WHERE evidence_version_id IN "+PAGE_IDS+"</script>")
    List<ReviewRow> reviews(@Param("versionIds") List<String> versionIds);

    @Select("""
            <script>SELECT c.version_id,c.id AS chain_submission_id,c.status AS anchor_status,c.confirmed_tx_id,
                   a.block_number,a.validation_code FROM chain_submission c LEFT JOIN chain_attempt a
                       ON a.submission_id=c.id AND a.tx_id=c.confirmed_tx_id
            WHERE c.version_id IN
            """+PAGE_IDS+"</script>")
    List<AnchorRow> anchors(@Param("versionIds") List<String> versionIds);

    @Select("""
            <script>SELECT version_id,COUNT(*) AS grant_count,
                SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END) AS pending_grant_count,
                SUM(CASE WHEN status='APPROVED' AND valid_until>#{asOf} THEN 1 ELSE 0 END) AS active_grant_count,
                SUM(CASE WHEN status='REJECTED' THEN 1 ELSE 0 END) AS rejected_grant_count,
                SUM(CASE WHEN status='REVOKED' THEN 1 ELSE 0 END) AS revoked_grant_count,
                SUM(CASE WHEN status='APPROVED' AND valid_until&lt;=#{asOf} THEN 1 ELSE 0 END) AS expired_grant_count
            FROM access_grant WHERE version_id IN
            """+PAGE_IDS+" GROUP BY version_id</script>")
    List<SharingRow> sharing(@Param("versionIds") List<String> versionIds,@Param("asOf") Instant asOf);

    @Select("<script>SELECT version_id,COUNT(*) AS total FROM verification_record WHERE version_id IN "+PAGE_IDS+
            " GROUP BY version_id</script>")
    List<CountRow> verifications(@Param("versionIds") List<String> versionIds);

    record TaskRow(String id,String ownerOrgId,String operatorOrgId) {}
    record VersionRow(String versionId,String requirementId,String evidenceId,int versionNo,String subtaskId,String executionId,
                      String uploadId,String filename,String contentType,long size,String sha256,Instant createdAt,
                      boolean currentExecution,boolean latestInExecution) {}
    record CountRow(String versionId,long total) {}
    record ReviewRow(String versionId,String reviewRecordId,String decision,String reviewerId,Instant reviewedAt) {}
    record AnchorRow(String versionId,String chainSubmissionId,String anchorStatus,String confirmedTxId,Long blockNumber,Integer validationCode) {}
    record SharingRow(String versionId,long grantCount,long pendingGrantCount,long activeGrantCount,long rejectedGrantCount,
                      long revokedGrantCount,long expiredGrantCount) {}
}
