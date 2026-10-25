package com.uav.inspection.device.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Identity-only reads; management/report DTOs deliberately use the separate safe DeviceMapper. */
public interface AuthDeviceMapper {
    String COLUMNS="id,operator_org_id AS org_id,code,token_hash AS token_digest,enabled";

    @Select("SELECT "+COLUMNS+" FROM drone WHERE id=#{id}")
    IdentityRow identityById(@Param("id") String id);

    @Select("SELECT "+COLUMNS+" FROM drone WHERE id=#{id} FOR UPDATE")
    IdentityRow lockIdentityById(@Param("id") String id);

    record IdentityRow(String id,String orgId,String code,@JsonIgnore String tokenDigest,boolean enabled) {
        @JsonIgnore
        @Override public String tokenDigest() { return tokenDigest; }
        @Override public String toString() {
            return "DeviceIdentity[id="+id+", orgId="+orgId+", code="+code+", enabled="+enabled+"]";
        }
    }
}
