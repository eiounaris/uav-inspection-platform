package com.uav.inspection.device.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface DroneMapper {
    @Select("""
            SELECT id, operator_org_id, code, token_hash, enabled, last_seen_at, lock_version,
                   created_at, updated_at FROM drone WHERE code = #{code}
            """)
    DroneRow findByCode(@Param("code") String code);

    @Insert("""
            INSERT INTO drone (id, operator_org_id, code, token_hash)
            VALUES (#{id}, #{orgId}, #{code}, #{tokenHash})
            """)
    int insertDrone(@Param("id") String id, @Param("orgId") String orgId,
                    @Param("code") String code, @Param("tokenHash") String tokenHash);

    record DroneRow(String id, String operatorOrgId, String code, String tokenHash,
                    boolean enabled, Instant lastSeenAt, long lockVersion,
                    Instant createdAt, Instant updatedAt) {}
}
