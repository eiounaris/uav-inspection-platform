package com.uav.inspection.identity.persistence;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

/** Task membership is independent of organization administration. */
public interface TaskScopeMapper {
    @Select("SELECT COUNT(*) FROM task_member WHERE task_id = #{taskId} AND user_id = #{userId}")
    int countMembership(@Param("taskId") String taskId, @Param("userId") String userId);

    String GRANT_QUERY = """
            SELECT id,valid_until FROM access_grant
             WHERE version_id=#{versionId} AND applicant_id=#{userId}
               AND status='APPROVED' AND valid_until>UTC_TIMESTAMP(3)
             ORDER BY valid_until DESC,id LIMIT 1
            """;

    @Select(GRANT_QUERY)
    GrantScope grant(@Param("versionId") String versionId, @Param("userId") String userId);

    @Select(GRANT_QUERY + " FOR UPDATE")
    GrantScope lockGrant(@Param("versionId") String versionId, @Param("userId") String userId);

    @Select("SELECT UTC_TIMESTAMP(3)")
    Instant now();

    record GrantScope(String id, Instant validUntil) {}
}
