package com.uav.inspection.messaging.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OutboxMapper {
    @Insert("""
            INSERT INTO outbox_event (event_id,event_type,biz_id,dispatch_no,payload,status,next_send_at)
            VALUES (#{eventId},#{eventType},#{bizId},0,#{payload},'PENDING',UTC_TIMESTAMP(3))
            """)
    int insert(@Param("eventId") String eventId, @Param("eventType") String eventType,
               @Param("bizId") String bizId, @Param("payload") String payload);

    @Select("""
            SELECT event_id,payload,attempt_count FROM outbox_event
             WHERE (status='PENDING' AND next_send_at<=UTC_TIMESTAMP(3))
                OR (status='SENDING' AND lease_until<=UTC_TIMESTAMP(3))
             ORDER BY next_send_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    PendingRow lockDue();

    @Update("""
            UPDATE outbox_event SET status='SENDING',lease_token=#{token},
                lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3)),attempt_count=attempt_count+1
             WHERE event_id=#{id}
            """)
    int claim(@Param("id") String id, @Param("token") String token);

    @Update("""
            UPDATE outbox_event SET status='SENT',sent_at=UTC_TIMESTAMP(3),last_error=NULL,
                lease_token=NULL,lease_until=NULL
             WHERE event_id=#{id} AND status='SENDING' AND lease_token=#{token}
               AND lease_until>UTC_TIMESTAMP(3)
            """)
    int sent(@Param("id") String id, @Param("token") String token);

    @Update("""
            UPDATE outbox_event SET status='PENDING',last_error=#{code},lease_token=NULL,lease_until=NULL,
                next_send_at=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(3))
             WHERE event_id=#{id} AND status='SENDING' AND lease_token=#{token}
               AND lease_until>UTC_TIMESTAMP(3)
            """)
    int retry(@Param("id") String id, @Param("token") String token,
              @Param("code") String code, @Param("delay") int delay);

    record PendingRow(String eventId, String payload, int attemptCount) {}
}
