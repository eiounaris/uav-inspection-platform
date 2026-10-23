package com.uav.inspection.evidence.verification.persistence;

import com.uav.inspection.evidence.verification.VerificationModels.VerificationView;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface VerificationMapper {
    String RECORD_COLUMNS="id,version_id,user_id,source,actual_sha256,ledger_sha256,actual_size,ledger_tx_id,result,reason,verified_at";

    /** The saved SQL digest is deliberately absent: every verification reads the actual bytes. */
    @Select("""
            SELECT v.id,v.evidence_id,v.version_no,v.task_id,v.object_key,v.submitter_org_id,t.owner_org_id,t.operator_org_id
            FROM evidence_version v JOIN inspection_task t ON t.id=v.task_id WHERE v.id=#{id}
            """)
    VersionRow version(@Param("id") String id);

    @Select("SELECT id FROM inspection_task WHERE id=#{id} FOR UPDATE")
    String lockTask(@Param("id") String id);

    @Insert("""
            INSERT INTO verification_record(id,version_id,user_id,source,actual_sha256,ledger_sha256,actual_size,ledger_tx_id,result,reason)
            VALUES(#{id},#{versionId},#{userId},#{source},#{actualSha256},#{ledgerSha256},#{actualSize},#{ledgerTxId},#{result},#{reason})
            """)
    int insert(VerificationView view);

    @Select("SELECT "+RECORD_COLUMNS+" FROM verification_record WHERE id=#{id}")
    VerificationView record(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM verification_record WHERE version_id=#{versionId}")
    long count(@Param("versionId") String versionId);

    @Select("SELECT "+RECORD_COLUMNS+" FROM verification_record WHERE version_id=#{versionId} ORDER BY verified_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<VerificationView> page(@Param("versionId") String versionId,@Param("size") int size,@Param("offset") long offset);

    record VersionRow(String id,String evidenceId,int versionNo,String taskId,String objectKey,String submitterOrgId,
                      String ownerOrgId,String operatorOrgId) {}
}
