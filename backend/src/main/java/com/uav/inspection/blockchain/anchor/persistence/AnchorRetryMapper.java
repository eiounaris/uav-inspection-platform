package com.uav.inspection.blockchain.anchor.persistence;

import org.apache.ibatis.annotations.*;

import java.time.Instant;

/** Manual intent history never rewrites a previous event or Fabric transaction attempt. */
public interface AnchorRetryMapper {
    @Select("SELECT task_id FROM evidence_version WHERE id=#{id}")
    String taskIdForVersion(@Param("id") String id);

    @Select("SELECT id,owner_org_id,operator_org_id,status FROM inspection_task WHERE id=#{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);

    @Select("SELECT id,task_id FROM evidence_version WHERE id=#{id} FOR UPDATE")
    VersionRow lockVersion(@Param("id") String id);

    @Select("""
            SELECT id,version_id,status,retryable,lease_token,lease_until
            FROM chain_submission WHERE version_id=#{versionId} FOR UPDATE
            """)
    SubmissionRow lockSubmission(@Param("versionId") String versionId);

    @Select("""
            SELECT id,submission_id,requested_by,client_request_id,request_hash,reason,
                   dispatch_no,baseline_attempt_no,event_id,created_at
            FROM anchor_retry_request
            WHERE submission_id=#{submissionId} AND requested_by=#{actorId} AND client_request_id=#{requestId}
            """)
    RequestRow request(@Param("submissionId") String submissionId,@Param("actorId") String actorId,
                       @Param("requestId") String requestId);

    @Select("""
            SELECT o.event_id,o.dispatch_no,c.status AS consumption_status
            FROM outbox_event o LEFT JOIN consumed_event c
              ON c.consumer_code='EVIDENCE_ANCHOR' AND c.event_id=o.event_id
            WHERE o.biz_id=#{submissionId} AND o.event_type='EVIDENCE_ANCHOR_REQUESTED'
            ORDER BY o.dispatch_no DESC LIMIT 1
            """)
    DispatchRow latestDispatch(@Param("submissionId") String submissionId);

    @Select("SELECT COALESCE(MAX(attempt_no),0) FROM chain_attempt WHERE submission_id=#{submissionId}")
    int latestAttemptNo(@Param("submissionId") String submissionId);

    @Update("""
            UPDATE chain_submission
            SET status=CASE WHEN status='FAILED' THEN 'PENDING' ELSE status END,
                next_action_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND lease_token IS NULL AND lease_until IS NULL
              AND (status IN ('PENDING','SUBMITTED','UNKNOWN') OR (status='FAILED' AND retryable=TRUE))
            """)
    int schedule(@Param("id") String id);

    @Insert("""
            INSERT INTO anchor_retry_request(id,submission_id,requested_by,client_request_id,request_hash,
                reason,dispatch_no,baseline_attempt_no,event_id)
            VALUES(#{id},#{submissionId},#{requestedBy},#{clientRequestId},#{requestHash},#{reason},
                #{dispatchNo},#{baselineAttemptNo},#{eventId})
            """)
    int insert(RequestRow row);

    @Select("""
            SELECT id,submission_id,requested_by,client_request_id,request_hash,reason,
                   dispatch_no,baseline_attempt_no,event_id,created_at
            FROM anchor_retry_request WHERE id=#{id}
            """)
    RequestRow find(@Param("id") String id);

    record TaskRow(String id,String ownerOrgId,String operatorOrgId,String status) {}
    record VersionRow(String id,String taskId) {}
    record SubmissionRow(String id,String versionId,String status,boolean retryable,String leaseToken,Instant leaseUntil) {}
    record DispatchRow(String eventId,int dispatchNo,String consumptionStatus) {}
    record RequestRow(String id,String submissionId,String requestedBy,String clientRequestId,String requestHash,
                      String reason,int dispatchNo,int baselineAttemptNo,String eventId,Instant createdAt) {}
}
