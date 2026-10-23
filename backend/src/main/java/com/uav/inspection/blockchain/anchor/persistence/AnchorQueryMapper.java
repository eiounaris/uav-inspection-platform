package com.uav.inspection.blockchain.anchor.persistence;

import com.uav.inspection.blockchain.anchor.AnchorViews.AnchorView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface AnchorQueryMapper {
    @Select("""
            SELECT v.id,v.evidence_id,v.version_no,v.requirement_id,v.task_id,v.subtask_id,v.execution_id,
                   u.filename,u.content_type,v.size,v.sha256,v.review_status,v.created_at,t.owner_org_id,t.operator_org_id
              FROM evidence_version v JOIN upload_session u ON u.id=v.upload_session_id
                   JOIN inspection_task t ON t.id=v.task_id
             WHERE v.id=#{versionId}
            """)
    VersionRow version(@Param("versionId") String versionId);

    @Select("""
            SELECT s.id AS submission_id,s.version_id,s.status,s.confirmed_tx_id,s.confirmed_by,
                   s.retryable,s.retry_count,s.next_action_at,s.last_error,
                   a.tx_id AS transaction_id,a.phase AS attempt_phase,a.validation_code,a.block_number
              FROM chain_submission s LEFT JOIN chain_attempt a ON a.submission_id=s.id
                   AND a.attempt_no=(SELECT MAX(latest.attempt_no) FROM chain_attempt latest WHERE latest.submission_id=s.id)
             WHERE s.version_id=#{versionId}
            """)
    AnchorView anchor(@Param("versionId") String versionId);

    record VersionRow(String id, String evidenceId, int versionNo, String requirementId, String taskId,
                      String subtaskId, String executionId, String filename, String contentType, long size,
                      String sha256, String reviewStatus, Instant createdAt, String ownerOrgId, String operatorOrgId) {}
}
