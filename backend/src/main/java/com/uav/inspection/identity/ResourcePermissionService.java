package com.uav.inspection.identity;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Current task membership or an explicit grant for exactly one fixed evidence version. */
@Service
@Profile("database")
public class ResourcePermissionService {
    public static final String OWNER = "00000000-0000-4000-8000-000000000001";
    public static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private final IdentityMapper identities;
    private final IdentityService authentication;
    private final TaskScopeMapper scopes;

    public ResourcePermissionService(IdentityMapper identities, IdentityService authentication,
                                     TaskScopeMapper scopes) {
        this.identities = identities;
        this.authentication = authentication;
        this.scopes = scopes;
    }

    /** A write caller must already hold its task lock and an active database transaction. */
    public CurrentUser currentActor(CurrentUser supplied, boolean forUpdate) {
        if (supplied == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (forUpdate) {
            identities.findUserByIdForUpdate(supplied.id());
        }
        // Re-read database permissions and reject disabled accounts or an obsolete token version.
        return authentication.authenticate(supplied.id(), supplied.tokenVersion());
    }

    public void requireCreate(CurrentUser current) {
        if (!OWNER.equals(current.orgId()) || !current.roles().contains(Role.OWNER_TASK_ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public boolean canReadTasks(CurrentUser current) {
        if (current == null) {
            return false;
        }
        return OWNER.equals(current.orgId()) && current.roles().stream().anyMatch(role ->
                role == Role.OWNER_TASK_ADMIN || role == Role.OWNER_REVIEWER || role == Role.OWNER_DATA_APPROVER)
                || OPERATOR.equals(current.orgId()) && current.roles().stream().anyMatch(role ->
                role == Role.OPERATOR_TASK_ADMIN || role == Role.OPERATOR_USER);
    }

    public void requireTask(CurrentUser current, String taskId, String ownerOrgId, String operatorOrgId,
                            Role requiredRole) {
        if (current == null || requiredRole != null && !current.roles().contains(requiredRole)
                || !canReadTask(current, taskId, ownerOrgId, operatorOrgId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public boolean canReadTask(CurrentUser current, String taskId, String ownerOrgId, String operatorOrgId) {
        if (current == null) return false;
        boolean inTaskOrganization = OWNER.equals(current.orgId()) && current.orgId().equals(ownerOrgId)
                || OPERATOR.equals(current.orgId()) && current.orgId().equals(operatorOrgId);
        return inTaskOrganization && canReadTasks(current) && scopes.countMembership(taskId, current.id()) == 1;
    }

    /** Task-wide audit and business relations require a managing or reviewing member. */
    public void requireTaskHistory(CurrentUser current, String taskId, String ownerOrgId, String operatorOrgId) {
        boolean managing = current != null && (
                OWNER.equals(current.orgId()) && current.roles().stream().anyMatch(role ->
                        role == Role.OWNER_TASK_ADMIN || role == Role.OWNER_REVIEWER || role == Role.OWNER_DATA_APPROVER)
                || OPERATOR.equals(current.orgId()) && current.roles().contains(Role.OPERATOR_TASK_ADMIN));
        if (!managing) throw new BusinessException(ErrorCode.FORBIDDEN);
        requireTask(current, taskId, ownerOrgId, operatorOrgId, null);
    }

    /** The caller has reauthenticated; a locking call also holds the parent task lock. */
    public VersionAccess requireVersion(CurrentUser current, String versionId, String taskId,
                                        String ownerOrgId, String operatorOrgId, boolean forUpdate) {
        if (canReadTask(current, taskId, ownerOrgId, operatorOrgId)) {
            return new VersionAccess(true, null, null);
        }
        if (current == null || !(OWNER.equals(current.orgId()) || OPERATOR.equals(current.orgId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        var grant = forUpdate ? scopes.lockGrant(versionId, current.id()) : scopes.grant(versionId, current.id());
        // Evaluate time after a possible row-lock wait, not only in the original SELECT predicate.
        if (grant == null || !grant.validUntil().isAfter(scopes.now())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return new VersionAccess(false, grant.id(), grant.validUntil());
    }

    public record VersionAccess(boolean taskPath, String grantId, Instant validUntil) {}
}
