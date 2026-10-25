package com.uav.inspection.device.persistence;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/** Business projections intentionally never select the device token digest. */
public interface DeviceMapper {
    String DEVICE_COLUMNS="id,operator_org_id,code,enabled,last_seen_at,lock_version,created_at,updated_at";
    String EVENT_COLUMNS="id,drone_id,boot_id,seq,event_id,execution_id,event_type,progress,payload_fingerprint AS fingerprint,received_at,applied_progress";

    @Select("SELECT UTC_TIMESTAMP(3)") Instant databaseNow();
    @Select("SELECT "+DEVICE_COLUMNS+" FROM drone WHERE id=#{id}") DeviceRow findDevice(@Param("id") String id);
    @Select("SELECT "+DEVICE_COLUMNS+" FROM drone WHERE id=#{id} FOR UPDATE") DeviceRow lockDevice(@Param("id") String id);
    @Select("SELECT "+DEVICE_COLUMNS+" FROM drone WHERE code=#{code}") DeviceRow findByCode(@Param("code") String code);
    @Select("SELECT COUNT(*) FROM drone WHERE operator_org_id=#{orgId}") long countDevices(@Param("orgId") String orgId);
    @Select("SELECT "+DEVICE_COLUMNS+" FROM drone WHERE operator_org_id=#{orgId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<DeviceRow> listDevices(@Param("orgId") String orgId,@Param("size") int size,@Param("offset") long offset);
    @Insert("INSERT INTO drone(id,operator_org_id,code,token_hash) VALUES(#{id},#{orgId},#{code},#{tokenDigest})")
    int insertDevice(@Param("id") String id,@Param("orgId") String orgId,@Param("code") String code,@Param("tokenDigest") String tokenDigest);
    @Update("""
            UPDATE drone SET enabled=#{enabled},lock_version=lock_version+1
            WHERE id=#{id} AND operator_org_id=#{orgId} AND lock_version=#{version}
            """)
    int setEnabled(@Param("id") String id,@Param("orgId") String orgId,@Param("enabled") boolean enabled,@Param("version") long version);

    @Select("""
            SELECT e.id,e.subtask_id,s.task_id,e.drone_id,e.status,e.progress,e.lock_version
            FROM task_execution e JOIN inspection_subtask s ON s.id=e.subtask_id WHERE e.id=#{id}
            """)
    ExecutionRow findExecution(@Param("id") String id);
    @Select("SELECT id,operator_org_id,status FROM inspection_task WHERE id=#{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);
    @Select("SELECT id,task_id,current_execution_id FROM inspection_subtask WHERE id=#{id} FOR UPDATE")
    SubtaskRow lockSubtask(@Param("id") String id);
    @Select("""
            SELECT e.id,e.subtask_id,s.task_id,e.drone_id,e.status,e.progress,e.lock_version
            FROM task_execution e JOIN inspection_subtask s ON s.id=e.subtask_id WHERE e.id=#{id} FOR UPDATE
            """)
    ExecutionRow lockExecution(@Param("id") String id);
    @Select("SELECT "+EVENT_COLUMNS+" FROM device_event WHERE drone_id=#{droneId} AND boot_id=#{bootId} AND seq=#{seq}")
    EventRow findBySequence(@Param("droneId") String droneId,@Param("bootId") String bootId,@Param("seq") long seq);
    @Select("SELECT "+EVENT_COLUMNS+" FROM device_event WHERE drone_id=#{droneId} AND event_id=#{eventId}")
    EventRow findByEventId(@Param("droneId") String droneId,@Param("eventId") String eventId);
    @Insert("""
            INSERT INTO device_event(id,drone_id,boot_id,seq,event_id,execution_id,event_type,progress,payload_fingerprint,received_at,applied_progress)
            VALUES(#{id},#{droneId},#{bootId},#{seq},#{eventId},#{executionId},#{eventType},#{progress},#{fingerprint},#{receivedAt},#{appliedProgress})
            """)
    int insertEvent(EventRow event);
    @Update("""
            UPDATE drone SET last_seen_at=GREATEST(COALESCE(last_seen_at,#{receivedAt}),#{receivedAt}),lock_version=lock_version+1
            WHERE id=#{id} AND enabled=TRUE
            """)
    int touchLastSeen(@Param("id") String id,@Param("receivedAt") Instant receivedAt);
    @Update("UPDATE task_execution SET progress=GREATEST(progress,#{progress}),lock_version=lock_version+1 WHERE id=#{id} AND status='RUNNING'")
    int updateProgress(@Param("id") String id,@Param("progress") int progress);
    @Insert("""
            INSERT INTO audit_log(id,actor_type,actor_id,org_id,action,resource_type,resource_id,trace_id,result)
            VALUES(#{id},'DEVICE',#{droneId},#{orgId},#{action},'TASK_EXECUTION',#{executionId},#{traceId},'SUCCESS')
            """)
    int auditReport(@Param("id") String id,@Param("droneId") String droneId,@Param("orgId") String orgId,
                    @Param("action") String action,@Param("executionId") String executionId,@Param("traceId") String traceId);

    record DeviceRow(String id,String operatorOrgId,String code,boolean enabled,Instant lastSeenAt,long lockVersion,
                     Instant createdAt,Instant updatedAt) {}
    record TaskRow(String id,String operatorOrgId,String status) {}
    record SubtaskRow(String id,String taskId,String currentExecutionId) {}
    record ExecutionRow(String id,String subtaskId,String taskId,String droneId,String status,int progress,long lockVersion) {}
    record EventRow(String id,String droneId,String bootId,long seq,String eventId,String executionId,String eventType,
                    Integer progress,String fingerprint,Instant receivedAt,int appliedProgress) {}
}
