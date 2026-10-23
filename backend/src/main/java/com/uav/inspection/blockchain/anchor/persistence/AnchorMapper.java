package com.uav.inspection.blockchain.anchor.persistence;

import com.uav.inspection.messaging.MessageEvent;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/** Byte checkpoints stay inside this persistence boundary and are never API DTOs. */
public interface AnchorMapper {
    String SUBMISSION_COLUMNS="""
            s.id,s.version_id,s.status,s.confirmed_tx_id,s.confirmed_by,s.retryable,s.retry_count,
            s.next_action_at,s.lease_token,s.lease_until,s.last_error,s.created_at,s.updated_at,
            v.task_id,v.evidence_id,v.version_no,v.sha256,v.submitter_org_id
            """;
    String ATTEMPT_COLUMNS="id,submission_id,attempt_no,tx_id,phase,transaction_bytes,commit_bytes,validation_code,block_number,last_error,created_at,updated_at";

    @Select("SELECT UTC_TIMESTAMP(3)")
    Instant now();

    @Select("SELECT "+SUBMISSION_COLUMNS+" FROM chain_submission s JOIN evidence_version v ON v.id=s.version_id WHERE s.id=#{id}")
    SubmissionRow find(@Param("id") String id);

    @Select("SELECT "+SUBMISSION_COLUMNS+" FROM chain_submission s JOIN evidence_version v ON v.id=s.version_id WHERE s.id=#{id} FOR UPDATE")
    SubmissionRow lock(@Param("id") String id);

    @Select("SELECT id FROM inspection_task WHERE id=#{id} FOR UPDATE")
    String lockTask(@Param("id") String id);

    @Select("SELECT "+ATTEMPT_COLUMNS+" FROM chain_attempt WHERE submission_id=#{id} ORDER BY attempt_no DESC LIMIT 1")
    AttemptRow latest(@Param("id") String id);

    @Select("SELECT "+ATTEMPT_COLUMNS+" FROM chain_attempt WHERE id=#{id}")
    AttemptRow attempt(@Param("id") String id);

    @Select("""
            SELECT event_id,event_type,biz_id,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.traceId')) AS trace_id
            FROM outbox_event WHERE event_id=#{eventId} AND event_type='EVIDENCE_ANCHOR_REQUESTED' AND biz_id=#{bizId}
            """)
    MessageEvent event(@Param("eventId") String eventId,@Param("bizId") String bizId);

    @Select("""
            SELECT o.event_id,o.event_type,o.biz_id,JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.traceId')) AS trace_id
            FROM chain_submission s JOIN outbox_event o ON o.biz_id=s.id
                AND o.event_type='EVIDENCE_ANCHOR_REQUESTED' AND o.dispatch_no=0
            WHERE (s.status IN ('PENDING','SUBMITTED','UNKNOWN') OR (s.status='FAILED' AND s.retryable=TRUE AND s.retry_count<10))
              AND (s.next_action_at IS NULL OR s.next_action_at<=UTC_TIMESTAMP(3))
              AND (s.lease_until IS NULL OR s.lease_until<=UTC_TIMESTAMP(3))
            ORDER BY s.updated_at,s.id LIMIT 10
            """)
    List<MessageEvent> due();

    @Select("SELECT status FROM consumed_event WHERE consumer_code='EVIDENCE_ANCHOR' AND event_id=#{eventId}")
    String consumption(@Param("eventId") String eventId);

    @Insert("""
            INSERT INTO consumed_event(consumer_code,event_id,status) VALUES('EVIDENCE_ANCHOR',#{eventId},#{status})
            ON DUPLICATE KEY UPDATE status=#{status},updated_at=UTC_TIMESTAMP(3)
            """)
    int consume(@Param("eventId") String eventId,@Param("status") String status);

    @Update("""
            UPDATE chain_submission SET lease_token=#{token},lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3)),next_action_at=NULL
            WHERE id=#{id} AND status<>'CONFIRMED'
              AND (status<>'FAILED' OR (retryable=TRUE AND retry_count<10))
              AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(3))
              AND (next_action_at IS NULL OR next_action_at<=UTC_TIMESTAMP(3))
            """)
    int claim(@Param("id") String id,@Param("token") String token);

    @Insert("""
            INSERT INTO chain_attempt(id,submission_id,attempt_no,tx_id,phase,transaction_bytes)
            SELECT #{id},s.id,#{attemptNo},#{txId},'PREPARED',#{transactionBytes} FROM chain_submission s
            WHERE s.id=#{submissionId} AND s.lease_token=#{token} AND s.lease_until>UTC_TIMESTAMP(3)
            """)
    int insertPrepared(@Param("id") String id,@Param("submissionId") String submissionId,@Param("attemptNo") int attemptNo,
                       @Param("txId") String txId,@Param("transactionBytes") byte[] transactionBytes,@Param("token") String token);

    @Update("""
            UPDATE chain_attempt a JOIN chain_submission s ON s.id=a.submission_id
            SET a.phase='SUBMITTING',a.last_error=NULL
            WHERE a.id=#{attemptId} AND s.id=#{id} AND s.lease_token=#{token} AND s.lease_until>UTC_TIMESTAMP(3)
            """)
    int submitting(@Param("id") String id,@Param("attemptId") String attemptId,@Param("token") String token);

    @Update("""
            UPDATE chain_attempt a JOIN chain_submission s ON s.id=a.submission_id
            SET a.phase='SUBMITTED',a.commit_bytes=#{bytes},a.last_error=NULL,s.status='SUBMITTED',s.last_error=NULL
            WHERE a.id=#{attemptId} AND a.tx_id=#{txId} AND s.id=#{id} AND s.lease_token=#{token} AND s.lease_until>UTC_TIMESTAMP(3)
            """)
    int submitted(@Param("id") String id,@Param("attemptId") String attemptId,@Param("token") String token,
                  @Param("txId") String txId,@Param("bytes") byte[] bytes);

    @Update("""
            UPDATE chain_attempt a JOIN chain_submission s ON s.id=a.submission_id
            SET a.phase=#{phase},a.validation_code=#{validationCode},a.block_number=#{blockNumber},a.last_error=NULL
            WHERE a.id=#{attemptId} AND a.tx_id=#{txId} AND s.id=#{id} AND s.lease_token=#{token} AND s.lease_until>UTC_TIMESTAMP(3)
            """)
    int outcome(@Param("id") String id,@Param("attemptId") String attemptId,@Param("token") String token,
                @Param("txId") String txId,@Param("phase") String phase,@Param("validationCode") int validationCode,
                @Param("blockNumber") long blockNumber);

    @Update("""
            UPDATE chain_attempt a JOIN chain_submission s ON s.id=a.submission_id
            SET a.phase=CASE WHEN #{unknown}=TRUE AND a.phase NOT IN ('VALID','INVALID') THEN 'UNKNOWN' ELSE a.phase END,
                a.last_error=#{code}
            WHERE a.id=#{attemptId} AND s.id=#{id} AND s.lease_token=#{token} AND s.lease_until>UTC_TIMESTAMP(3)
            """)
    int attemptError(@Param("id") String id,@Param("attemptId") String attemptId,@Param("token") String token,
                     @Param("unknown") boolean unknown,@Param("code") String code);

    @Update("""
            UPDATE chain_submission SET status=#{status},retryable=#{retryable},retry_count=retry_count+1,
                next_action_at=#{nextActionAt},last_error=#{code},lease_token=NULL,lease_until=NULL
            WHERE id=#{id} AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int checkpoint(@Param("id") String id,@Param("token") String token,@Param("status") String status,
                   @Param("retryable") boolean retryable,@Param("nextActionAt") Instant nextActionAt,@Param("code") String code);

    @Update("""
            UPDATE chain_submission SET status='CONFIRMED',confirmed_tx_id=#{txId},confirmed_by=#{msp},
                retryable=FALSE,next_action_at=NULL,last_error=NULL,lease_token=NULL,lease_until=NULL
            WHERE id=#{id} AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int confirm(@Param("id") String id,@Param("token") String token,@Param("txId") String txId,@Param("msp") String msp);

    @Insert("""
            INSERT INTO audit_log(id,actor_type,actor_id,org_id,action,resource_type,resource_id,trace_id,result)
            VALUES(#{id},'SYSTEM',NULL,#{orgId},#{action},'CHAIN_SUBMISSION',#{submissionId},#{traceId},'SUCCESS')
            """)
    int audit(@Param("id") String id,@Param("orgId") String orgId,@Param("action") String action,
              @Param("submissionId") String submissionId,@Param("traceId") String traceId);

    record SubmissionRow(String id,String versionId,String status,String confirmedTxId,String confirmedBy,
                         boolean retryable,int retryCount,Instant nextActionAt,String leaseToken,Instant leaseUntil,
                         String lastError,Instant createdAt,Instant updatedAt,String taskId,String evidenceId,
                         int versionNo,String sha256,String submitterOrgId) {}

    record AttemptRow(String id,String submissionId,int attemptNo,String txId,String phase,byte[] transactionBytes,
                      byte[] commitBytes,Integer validationCode,Long blockNumber,String lastError,Instant createdAt,Instant updatedAt) {
        public AttemptRow {
            transactionBytes=transactionBytes.clone();
            commitBytes=commitBytes==null?null:commitBytes.clone();
        }
        @Override public byte[] transactionBytes(){return transactionBytes.clone();}
        @Override public byte[] commitBytes(){return commitBytes==null?null:commitBytes.clone();}
    }
}
