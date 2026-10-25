package com.uav.inspection.sharing.persistence;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

public interface SharingMapper {
    String GRANT_COLUMNS="id,version_id,applicant_id,client_request_id,request_hash,status,reason,requested_valid_until,"
            +"approver_id,decision_reason,decided_at,valid_until,revoked_by,revoke_reason,revoked_at,lock_version,created_at,updated_at";

    @Select("SELECT UTC_TIMESTAMP(3)")
    Instant databaseNow();

    @Select("""
            SELECT v.id,v.task_id,t.owner_org_id,t.operator_org_id,v.review_status,c.status AS anchor_status
            FROM evidence_version v JOIN inspection_task t ON t.id=v.task_id
                LEFT JOIN chain_submission c ON c.version_id=v.id WHERE v.id=#{id}
            """)
    VersionScope version(@Param("id") String id);

    @Select("SELECT id,owner_org_id,operator_org_id FROM inspection_task WHERE id=#{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);

    @Select("SELECT "+GRANT_COLUMNS+" FROM access_grant WHERE applicant_id=#{applicantId} AND client_request_id=#{requestId}")
    GrantRow request(@Param("applicantId") String applicantId,@Param("requestId") String requestId);

    @Select("SELECT "+GRANT_COLUMNS+" FROM access_grant WHERE id=#{id}")
    GrantRow grant(@Param("id") String id);

    @Select("SELECT "+GRANT_COLUMNS+" FROM access_grant WHERE id=#{id} FOR UPDATE")
    GrantRow lockGrant(@Param("id") String id);

    @Insert("""
            INSERT INTO access_grant(id,version_id,applicant_id,client_request_id,request_hash,reason,requested_valid_until)
            SELECT #{id},#{versionId},#{applicantId},#{clientRequestId},#{requestHash},#{reason},#{requestedValidUntil}
            WHERE #{requestedValidUntil}>UTC_TIMESTAMP(3)
            """)
    int insert(GrantRow row);

    @Update("""
            UPDATE access_grant SET status=#{decision},approver_id=#{actorId},decision_reason=#{reason},
                decided_at=UTC_TIMESTAMP(3),valid_until=#{validUntil},lock_version=lock_version+1
            WHERE id=#{id} AND status='PENDING' AND lock_version=#{version}
                AND (#{decision}='REJECTED' OR (#{validUntil}>UTC_TIMESTAMP(3) AND #{validUntil}<=requested_valid_until))
            """)
    int decide(@Param("id") String id,@Param("version") long version,@Param("decision") String decision,
                @Param("actorId") String actorId,@Param("reason") String reason,@Param("validUntil") Instant validUntil);

    @Update("""
            UPDATE access_grant SET status='REVOKED',revoked_by=#{actorId},revoke_reason=#{reason},
                revoked_at=UTC_TIMESTAMP(3),lock_version=lock_version+1
            WHERE id=#{id} AND status='APPROVED' AND lock_version=#{version} AND valid_until>UTC_TIMESTAMP(3)
            """)
    int revoke(@Param("id") String id,@Param("version") long version,@Param("actorId") String actorId,@Param("reason") String reason);

    @Select("SELECT COUNT(*) FROM access_grant WHERE applicant_id=#{actorId}")
    long countMine(@Param("actorId") String actorId);

    @Select("SELECT "+GRANT_COLUMNS+" FROM access_grant WHERE applicant_id=#{actorId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<GrantRow> mine(@Param("actorId") String actorId,@Param("size") int size,@Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM access_grant g JOIN evidence_version v ON v.id=g.version_id WHERE v.task_id=#{taskId}")
    long countTask(@Param("taskId") String taskId);

    @Select("""
            SELECT g.id,g.version_id,g.applicant_id,g.client_request_id,g.request_hash,g.status,g.reason,g.requested_valid_until,
                g.approver_id,g.decision_reason,g.decided_at,g.valid_until,g.revoked_by,g.revoke_reason,g.revoked_at,
                g.lock_version,g.created_at,g.updated_at
            FROM access_grant g JOIN evidence_version v ON v.id=g.version_id WHERE v.task_id=#{taskId}
            ORDER BY g.created_at DESC,g.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<GrantRow> task(@Param("taskId") String taskId,@Param("size") int size,@Param("offset") long offset);

    record TaskRow(String id,String ownerOrgId,String operatorOrgId) {}
    record VersionScope(String id,String taskId,String ownerOrgId,String operatorOrgId,String reviewStatus,String anchorStatus) {}
    record GrantRow(String id,String versionId,String applicantId,String clientRequestId,String requestHash,String status,
                    String reason,Instant requestedValidUntil,String approverId,String decisionReason,Instant decidedAt,
                    Instant validUntil,String revokedBy,String revokeReason,Instant revokedAt,long lockVersion,
                    Instant createdAt,Instant updatedAt) {
        @Override public String toString() { return "GrantRow[id="+id+",status="+status+"]"; }
    }
}
