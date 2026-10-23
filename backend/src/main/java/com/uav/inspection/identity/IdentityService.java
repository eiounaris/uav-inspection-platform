package com.uav.inspection.identity;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.IdentityMapper.UserRow;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@Profile("database")
public class IdentityService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // A fixed cost-12 BCrypt encoding, used only to equalize work for unknown usernames.
    private static final String DUMMY_PASSWORD_HASH = "$2a$12$" + ".".repeat(53);

    private final IdentityMapper identities;
    private final AuditMapper audit;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public IdentityService(IdentityMapper identities, AuditMapper audit) {
        this.identities = identities;
        this.audit = audit;
    }

    @Transactional
    public CurrentUser login(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 64
                || password == null || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        UserRow initial = identities.findUserByUsername(username);
        boolean matched = passwords.matches(password,
                initial == null ? DUMMY_PASSWORD_HASH : initial.passwordHash());
        if (initial == null || !matched || !initial.enabled()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // BCrypt runs before the row lock. Re-read security state before returning a signing principal.
        UserRow current = identities.findUserByIdForUpdate(initial.id());
        if (current == null || !current.enabled() || !initial.passwordHash().equals(current.passwordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        CurrentUser user = principal(current);
        appendAudit(user, "AUTH_LOGIN", user.id());
        return user;
    }

    public CurrentUser authenticate(String userId, long tokenVersion) {
        if (userId == null || userId.isBlank() || tokenVersion < 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        UserRow user = identities.findUserById(userId);
        requireCurrent(user, tokenVersion);
        return principal(user);
    }

    @Transactional
    public void logout(CurrentUser actor) {
        requireActor(actor);
        // Never replace the request's original version with a newer version read from the database.
        int updated = identities.logoutVersion(actor.id(), actor.tokenVersion());
        if (updated == 1) {
            appendAudit(actor, "AUTH_LOGOUT", actor.id());
        } else if (updated != 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        // A concurrent logout or role change may already have invalidated this admitted request.
    }

    @Transactional
    public CurrentUser changeRoles(CurrentUser actor, String targetId, List<Role> roles,
                                   long expectedLockVersion) {
        requireActor(actor);
        if (roles == null || roles.isEmpty() || roles.size() > Role.values().length
                || roles.stream().anyMatch(role -> role == null) || new HashSet<>(roles).size() != roles.size()
                || expectedLockVersion < 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        List<UserRow> locked = identities.lockUsers(actor.id(), targetId);
        UserRow currentActor = locked.stream().filter(user -> actor.id().equals(user.id())).findFirst().orElse(null);
        requireCurrent(currentActor, actor.tokenVersion());
        CurrentUser verifiedActor = principal(currentActor);
        if (!verifiedActor.roles().contains(Role.ORG_ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        UserRow target = locked.stream().filter(user -> user.id().equals(targetId)).findFirst().orElse(null);
        if (target == null || !verifiedActor.orgId().equals(target.orgId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (roles.stream().anyMatch(role -> !role.isAllowedFor(target.orgId()))) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (target.lockVersion() != expectedLockVersion) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        List<Role> canonicalRoles = roles.stream().sorted().toList();
        int updated = identities.updateRoles(target.id(), target.orgId(),
                JSON.writeValueAsString(canonicalRoles), expectedLockVersion);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        appendAudit(verifiedActor, "USER_ROLES_CHANGED", target.id());
        UserRow changed = identities.findUserById(target.id());
        if (changed == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return principal(changed);
    }

    private void appendAudit(CurrentUser actor, String action, String resourceId) {
        String traceId = TraceContext.currentTraceId();
        if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,64}")) {
            traceId = UUID.randomUUID().toString();
        }
        int inserted = audit.insertSuccess(UUID.randomUUID().toString(), actor.id(), actor.orgId(),
                action, resourceId, traceId);
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static CurrentUser principal(UserRow row) {
        try {
            Role[] decoded = JSON.readValue(row.rolesJson(), Role[].class);
            List<Role> roles = List.copyOf(Arrays.asList(decoded));
            if (roles.isEmpty() || new HashSet<>(roles).size() != roles.size()
                    || roles.stream().anyMatch(role -> !role.isAllowedFor(row.orgId()))) {
                throw new IllegalArgumentException();
            }
            return new CurrentUser(row.id(), row.orgId(), row.username(), roles,
                    row.tokenVersion(), row.lockVersion());
        } catch (RuntimeException exception) {
            // Do not attach persistence/JSON values to a response or loggable exception cause.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static void requireCurrent(UserRow row, long tokenVersion) {
        if (row == null || !row.enabled() || row.tokenVersion() != tokenVersion) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private static void requireActor(CurrentUser actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
