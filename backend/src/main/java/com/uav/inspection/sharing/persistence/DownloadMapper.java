package com.uav.inspection.sharing.persistence;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

/** Immutable file metadata and the parent lock; grant scope is owned by ResourcePermissionService. */
public interface DownloadMapper {
    @Select("SELECT id,task_id,object_key FROM evidence_version WHERE id=#{id}")
    VersionRow version(@Param("id") String id);

    @Select("SELECT id,owner_org_id,operator_org_id FROM inspection_task WHERE id=#{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);

    @Select("SELECT UTC_TIMESTAMP(3)")
    Instant now();

    record VersionRow(String id,String taskId,String objectKey) {
        @Override public String toString() { return "VersionRow[id="+id+",taskId="+taskId+"]"; }
    }
    record TaskRow(String id,String ownerOrgId,String operatorOrgId) {}
}
