package com.uav.inspection.evidence.finalization.persistence;

import com.uav.inspection.messaging.MessageEvent;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

public interface FinalizationMapper {
    @Update("""
            UPDATE upload_session SET status='VERIFYING',next_action_at=UTC_TIMESTAMP(3),
                lock_version=lock_version+1,last_error=NULL
            WHERE id=#{id} AND status='UPLOADING' AND lock_version=#{version} AND expires_at>UTC_TIMESTAMP(3)
            """)
    int freeze(@Param("id") String id, @Param("version") long version);

    @Select("SELECT lease_token,lease_until,retry_count,next_action_at FROM upload_session WHERE id=#{id}")
    WorkState state(@Param("id") String id);

    @Select("""
            SELECT event_id,event_type,biz_id,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.traceId')) AS trace_id
            FROM outbox_event WHERE event_id=#{eventId} AND event_type='FILE_FINALIZE_REQUESTED' AND biz_id=#{bizId}
            """)
    MessageEvent findEvent(@Param("eventId") String eventId, @Param("bizId") String bizId);

    @Select("""
            SELECT o.event_id,o.event_type,o.biz_id,JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.traceId')) AS trace_id
            FROM upload_session u JOIN outbox_event o ON o.biz_id=u.id
                AND o.event_type='FILE_FINALIZE_REQUESTED' AND o.dispatch_no=0
            WHERE u.status='VERIFYING' AND (u.next_action_at IS NULL OR u.next_action_at<=UTC_TIMESTAMP(3))
                AND (u.lease_until IS NULL OR u.lease_until<=UTC_TIMESTAMP(3))
            ORDER BY u.updated_at,u.id LIMIT 10
            """)
    List<MessageEvent> due();

    @Select("SELECT status FROM consumed_event WHERE consumer_code='FILE_FINALIZE' AND event_id=#{eventId}")
    String consumption(@Param("eventId") String eventId);

    @Insert("""
            INSERT INTO consumed_event(consumer_code,event_id,status) VALUES('FILE_FINALIZE',#{eventId},'PROCESSING')
            ON DUPLICATE KEY UPDATE status='PROCESSING',updated_at=UTC_TIMESTAMP(3)
            """)
    int processing(@Param("eventId") String eventId);

    @Insert("""
            INSERT INTO consumed_event(consumer_code,event_id,status) VALUES('FILE_FINALIZE',#{eventId},'DONE')
            ON DUPLICATE KEY UPDATE status='DONE',updated_at=UTC_TIMESTAMP(3)
            """)
    int done(@Param("eventId") String eventId);

    @Update("""
            UPDATE upload_session SET lease_token=#{token},lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3)),
                next_action_at=NULL,lock_version=lock_version+1
            WHERE id=#{id} AND status='VERIFYING'
                AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(3))
                AND (next_action_at IS NULL OR next_action_at<=UTC_TIMESTAMP(3))
            """)
    int claim(@Param("id") String id, @Param("token") String token);

    @Update("""
            UPDATE upload_session SET lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3)),
                lock_version=lock_version+1
            WHERE id=#{id} AND status='VERIFYING' AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int renew(@Param("id") String id, @Param("token") String token);

    @Update("""
            UPDATE upload_session SET status='COMPLETED',lease_token=NULL,lease_until=NULL,next_action_at=NULL,
                last_error=NULL,lock_version=lock_version+1
            WHERE id=#{id} AND status='VERIFYING' AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int complete(@Param("id") String id, @Param("token") String token);

    @Update("""
            UPDATE upload_session SET status=#{status},lease_token=NULL,lease_until=NULL,
                next_action_at=#{nextActionAt},last_error=#{error},retry_count=retry_count+1,lock_version=lock_version+1
            WHERE id=#{id} AND status='VERIFYING' AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int checkpoint(@Param("id") String id, @Param("token") String token, @Param("status") String status,
                   @Param("nextActionAt") Instant nextActionAt, @Param("error") String error);

    @Select("SELECT next_version_no FROM material_requirement WHERE id=#{id} FOR UPDATE")
    int lockVersionNumber(@Param("id") String id);

    @Update("""
            UPDATE material_requirement SET next_version_no=next_version_no+1,lock_version=lock_version+1
            WHERE id=#{id} AND next_version_no=#{versionNo}
            """)
    int advanceVersion(@Param("id") String id, @Param("versionNo") int versionNo);

    @Insert("""
            INSERT INTO evidence_version(id,requirement_id,evidence_id,task_id,subtask_id,execution_id,
                upload_session_id,version_no,uploader_user_id,uploader_drone_id,submitter_org_id,object_key,size,sha256)
            VALUES(#{id},#{requirementId},#{evidenceId},#{taskId},#{subtaskId},#{executionId},#{uploadSessionId},
                #{versionNo},#{uploaderUserId},#{uploaderDroneId},#{submitterOrgId},#{objectKey},#{size},#{sha256})
            """)
    int insertVersion(FixedVersion version);

    @Insert("""
            INSERT INTO chain_submission(id,version_id,status,next_action_at) VALUES(#{id},#{versionId},'PENDING',UTC_TIMESTAMP(3))
            """)
    int insertSubmission(@Param("id") String id, @Param("versionId") String versionId);

    @Update("""
            UPDATE inspection_task SET status='RUNNING',lock_version=lock_version+1
            WHERE id=#{id} AND status='WAITING_ACCEPTANCE'
            """)
    int reopenTask(@Param("id") String id);

    @Insert("""
            INSERT INTO audit_log(id,actor_type,actor_id,org_id,action,resource_type,resource_id,trace_id,result)
            VALUES(#{id},'SYSTEM',NULL,#{orgId},#{action},'UPLOAD_SESSION',#{uploadId},#{traceId},'SUCCESS')
            """)
    int audit(@Param("id") String id, @Param("orgId") String orgId, @Param("action") String action,
              @Param("uploadId") String uploadId, @Param("traceId") String traceId);

    record WorkState(String leaseToken, Instant leaseUntil, int retryCount, Instant nextActionAt) {}
    record FixedVersion(String id,String requirementId,String evidenceId,String taskId,String subtaskId,
                        String executionId,String uploadSessionId,int versionNo,String uploaderUserId,
                        String uploaderDroneId,String submitterOrgId,String objectKey,long size,String sha256) {}
}
