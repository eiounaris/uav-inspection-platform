package com.uav.inspection.identity.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/** Internal persistence records; password hashes are never HTTP response DTOs. */
public interface IdentityMapper {
    @Select("SELECT id, code, name, msp_id FROM organization ORDER BY code")
    List<OrganizationRow> findOrganizations();

    @Select("""
            SELECT id, org_id, username, password_hash, CAST(roles AS CHAR) AS roles_json,
                   enabled, token_version, lock_version, created_at, updated_at
              FROM app_user WHERE username = #{username}
            """)
    UserRow findUserByUsername(@Param("username") String username);

    @Select("""
            SELECT id, org_id, username, password_hash, CAST(roles AS CHAR) AS roles_json,
                   enabled, token_version, lock_version, created_at, updated_at
              FROM app_user WHERE id = #{id}
            """)
    UserRow findUserById(@Param("id") String id);

    @Select("""
            SELECT id, org_id, username, password_hash, CAST(roles AS CHAR) AS roles_json,
                   enabled, token_version, lock_version, created_at, updated_at
              FROM app_user WHERE id = #{id} FOR UPDATE
            """)
    UserRow findUserByIdForUpdate(@Param("id") String id);

    @Select("""
            SELECT id, org_id, username, password_hash, CAST(roles AS CHAR) AS roles_json,
                   enabled, token_version, lock_version, created_at, updated_at
              FROM app_user WHERE id IN (#{actorId}, #{targetId}) ORDER BY id FOR UPDATE
            """)
    List<UserRow> lockUsers(@Param("actorId") String actorId, @Param("targetId") String targetId);

    @Update("""
            UPDATE app_user
               SET token_version = token_version + 1, lock_version = lock_version + 1
             WHERE id = #{id} AND token_version = #{tokenVersion} AND enabled = 1
            """)
    int logoutVersion(@Param("id") String id, @Param("tokenVersion") long tokenVersion);

    @Update("""
            UPDATE app_user
               SET roles = CAST(#{rolesJson} AS JSON), token_version = token_version + 1,
                   lock_version = lock_version + 1
             WHERE id = #{id} AND org_id = #{orgId} AND lock_version = #{expectedLockVersion}
            """)
    int updateRoles(@Param("id") String id, @Param("orgId") String orgId,
                    @Param("rolesJson") String rolesJson, @Param("expectedLockVersion") long expectedLockVersion);

    @Insert("""
            INSERT INTO app_user (id, org_id, username, password_hash, roles)
            VALUES (#{id}, #{orgId}, #{username}, #{passwordHash}, CAST(#{rolesJson} AS JSON))
            """)
    int insertUser(@Param("id") String id, @Param("orgId") String orgId,
                   @Param("username") String username, @Param("passwordHash") String passwordHash,
                   @Param("rolesJson") String rolesJson);

    record OrganizationRow(String id, String code, String name, String mspId) {}
    record UserRow(String id, String orgId, String username, String passwordHash, String rolesJson,
                   boolean enabled, long tokenVersion, long lockVersion, Instant createdAt, Instant updatedAt) {}
}
