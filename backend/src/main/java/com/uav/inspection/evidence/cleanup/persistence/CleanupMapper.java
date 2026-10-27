package com.uav.inspection.evidence.cleanup.persistence;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

public interface CleanupMapper {
    String TASK_COLUMNS="id,object_key,object_key_hash,upload_id,object_last_modified,object_size,status,attempt_count,next_action_at,lease_token,lease_until,last_error,created_at,updated_at";
    String SESSION_COLUMNS="id,task_id,submitter_org_id,status,expires_at,lease_token,lease_until,updated_at";

    @Select("SELECT UTC_TIMESTAMP(3)") Instant now();
    @Select("SELECT "+SESSION_COLUMNS+" FROM upload_session WHERE id=#{id}") SessionRow session(@Param("id") String id);
    @Select("SELECT id FROM inspection_task WHERE id=#{id} FOR UPDATE") String lockTask(@Param("id") String id);
    @Select("SELECT "+SESSION_COLUMNS+" FROM upload_session WHERE id=#{id} FOR UPDATE") SessionRow lockSession(@Param("id") String id);
    @Select("SELECT COUNT(*) FROM evidence_version WHERE object_key=#{key}") int formalReferences(@Param("key") String key);
    @Select("SELECT COUNT(*) FROM evidence_version WHERE upload_session_id=#{id}") int sessionVersions(@Param("id") String id);
    @Select("SELECT COUNT(*) FROM upload_part WHERE object_key=#{key}") int partReferences(@Param("key") String key);

    @Select("""
            SELECT id FROM upload_session WHERE status='UPLOADING' AND expires_at<=UTC_TIMESTAMP(3)
              AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(3)) ORDER BY expires_at,id LIMIT 10
            """)
    List<String> expiredSessions();

    @Update("""
            UPDATE upload_session SET status='EXPIRED',lease_token=NULL,lease_until=NULL,next_action_at=NULL,
                last_error='UPLOAD_SESSION_EXPIRED',lock_version=lock_version+1
            WHERE id=#{id} AND status='UPLOADING' AND expires_at<=UTC_TIMESTAMP(3)
              AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(3))
            """)
    int expire(@Param("id") String id);

    @Select("""
            SELECT u.id FROM upload_session u
            WHERE ((u.status='EXPIRED' AND u.expires_at<=TIMESTAMPADD(SECOND,-#{grace},UTC_TIMESTAMP(3)))
                OR (u.status='FAILED' AND u.updated_at<=TIMESTAMPADD(SECOND,-#{grace},UTC_TIMESTAMP(3))))
              AND (u.lease_until IS NULL OR u.lease_until<=UTC_TIMESTAMP(3))
              AND EXISTS(SELECT 1 FROM upload_part p WHERE p.session_id=u.id)
            ORDER BY u.updated_at,u.id LIMIT 10
            """)
    List<String> retiredSessions(@Param("grace") long grace);

    @Select("""
            SELECT session_id,part_no,object_key,size,created_at FROM upload_part
            WHERE session_id=#{id} ORDER BY part_no LIMIT #{limit}
            """)
    List<PartRow> parts(@Param("id") String id,@Param("limit") int limit);

    @Delete("DELETE FROM upload_part WHERE session_id=#{id} AND part_no=#{part} AND object_key=#{key}")
    int retirePart(@Param("id") String id,@Param("part") int part,@Param("key") String key);

    @Select("SELECT "+TASK_COLUMNS+" FROM storage_cleanup_task WHERE object_key_hash=#{hash}")
    TaskRow byHash(@Param("hash") String hash);
    @Select("SELECT "+TASK_COLUMNS+" FROM storage_cleanup_task WHERE id=#{id}") TaskRow task(@Param("id") String id);
    @Select("SELECT "+TASK_COLUMNS+" FROM storage_cleanup_task WHERE id=#{id} FOR UPDATE") TaskRow lockCleanup(@Param("id") String id);

    @Insert("""
            INSERT INTO storage_cleanup_task(id,object_key,object_key_hash,upload_id,object_last_modified,object_size)
            VALUES(#{id},#{objectKey},#{objectKeyHash},#{uploadId},#{objectLastModified},#{objectSize})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int insert(TaskRow task);

    @Update("""
            UPDATE storage_cleanup_task SET status='PENDING',next_action_at=UTC_TIMESTAMP(3),last_error=NULL,
                object_last_modified=#{modified},object_size=#{size}
            WHERE object_key_hash=#{hash} AND status IN ('DONE','SKIPPED')
            """)
    int rearm(@Param("hash") String hash,@Param("modified") Instant modified,@Param("size") long size);

    @Select("""
            SELECT id FROM storage_cleanup_task
            WHERE (status='PENDING' AND next_action_at<=UTC_TIMESTAMP(3))
               OR (status='SENDING' AND lease_until<=UTC_TIMESTAMP(3))
            ORDER BY COALESCE(next_action_at,lease_until),id LIMIT #{limit}
            """)
    List<String> due(@Param("limit") int limit);

    @Update("""
            UPDATE storage_cleanup_task SET status='SENDING',lease_token=#{token},
                lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3)),next_action_at=NULL,
                attempt_count=CASE WHEN attempt_count<2147483647 THEN attempt_count+1 ELSE attempt_count END
            WHERE id=#{id} AND ((status='PENDING' AND next_action_at<=UTC_TIMESTAMP(3))
                 OR (status='SENDING' AND lease_until<=UTC_TIMESTAMP(3)))
            """)
    int claim(@Param("id") String id,@Param("token") String token);

    @Update("""
            UPDATE storage_cleanup_task SET status='SKIPPED',lease_token=NULL,lease_until=NULL,next_action_at=NULL,last_error=#{code}
            WHERE id=#{id} AND ((status='PENDING' AND next_action_at<=UTC_TIMESTAMP(3))
                 OR (status='SENDING' AND lease_until<=UTC_TIMESTAMP(3)))
            """)
    int skipLocked(@Param("id") String id,@Param("code") String code);

    @Update("""
            UPDATE storage_cleanup_task SET status='SKIPPED',lease_token=NULL,lease_until=NULL,next_action_at=NULL,last_error=#{code}
            WHERE id=#{id} AND status='SENDING' AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int skipOwned(@Param("id") String id,@Param("token") String token,@Param("code") String code);

    @Update("""
            UPDATE storage_cleanup_task SET status='DONE',lease_token=NULL,lease_until=NULL,next_action_at=NULL,last_error=NULL
            WHERE id=#{id} AND status='SENDING' AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int done(@Param("id") String id,@Param("token") String token);

    @Update("""
            UPDATE storage_cleanup_task SET status='PENDING',lease_token=NULL,lease_until=NULL,
                next_action_at=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(3)),last_error='OBJECT_STORAGE_UNAVAILABLE'
            WHERE id=#{id} AND status='SENDING' AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int retry(@Param("id") String id,@Param("token") String token,@Param("delay") int delay);

    @Update("""
            UPDATE storage_cleanup_task SET lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3))
            WHERE id=#{id} AND status='SENDING' AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)
            """)
    int renew(@Param("id") String id,@Param("token") String token);

    @Insert("""
            INSERT INTO audit_log(id,actor_type,actor_id,org_id,action,resource_type,resource_id,trace_id,result)
            VALUES(#{id},'SYSTEM',NULL,#{orgId},#{action},#{resourceType},#{resourceId},#{traceId},'SUCCESS')
            """)
    int audit(@Param("id") String id,@Param("orgId") String orgId,@Param("action") String action,
              @Param("resourceType") String resourceType,@Param("resourceId") String resourceId,@Param("traceId") String traceId);

    record SessionRow(String id,String taskId,String submitterOrgId,String status,Instant expiresAt,
                      String leaseToken,Instant leaseUntil,Instant updatedAt) {}
    record PartRow(String sessionId,int partNo,String objectKey,long size,Instant createdAt) {}
    record TaskRow(String id,String objectKey,String objectKeyHash,String uploadId,Instant objectLastModified,
                   long objectSize,String status,int attemptCount,Instant nextActionAt,String leaseToken,
                   Instant leaseUntil,String lastError,Instant createdAt,Instant updatedAt) {}
}
