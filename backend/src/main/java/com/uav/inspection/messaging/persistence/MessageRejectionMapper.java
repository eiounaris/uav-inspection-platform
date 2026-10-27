package com.uav.inspection.messaging.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** Only bounded safe rejection metadata is accepted; the body never reaches SQL. */
public interface MessageRejectionMapper {
    @Insert("""
            INSERT INTO message_rejection
                (id,consumer_code,body_sha256,body_size,reason_code,message_id,created_at)
            VALUES (#{id},#{consumer},#{sha256},#{size},#{reason},#{messageId},UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE id=id
            """)
    int insertFirst(@Param("id") String id, @Param("consumer") String consumer,
                    @Param("sha256") String sha256, @Param("size") long size,
                    @Param("reason") String reason, @Param("messageId") String messageId);
}
