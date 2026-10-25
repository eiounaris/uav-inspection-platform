package com.uav.inspection.identity;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ResourcePermissionServiceTest {
    private static final String OWNER = ResourcePermissionService.OWNER;
    private static final String OPERATOR = ResourcePermissionService.OPERATOR;
    private final IdentityMapper identities = mock(IdentityMapper.class);
    private final IdentityService authentication = mock(IdentityService.class);
    private final TaskScopeMapper scopes = mock(TaskScopeMapper.class);
    private final ResourcePermissionService service = new ResourcePermissionService(identities, authentication, scopes);

    @Test
    void organizationAdministratorCannotReadTaskDataWithoutABusinessRole() {
        CurrentUser admin = user(OWNER, Role.ORG_ADMIN);
        assertForbidden(() -> service.requireTask(admin, "task", OWNER, OPERATOR, null));
        verifyNoInteractions(scopes);
    }

    @Test
    void taskAdministratorStillRequiresExplicitTaskMembership() {
        CurrentUser admin = user(OPERATOR, Role.OPERATOR_TASK_ADMIN);
        when(scopes.countMembership("task", admin.id())).thenReturn(0);
        assertForbidden(() -> service.requireTask(admin, "task", OWNER, OPERATOR, Role.OPERATOR_TASK_ADMIN));
    }

    @Test
    void memberWhoCanReadCannotUseAnAdministratorsWriteOperation() {
        CurrentUser reviewer = user(OWNER, Role.OWNER_REVIEWER);
        when(scopes.countMembership("task", reviewer.id())).thenReturn(1);
        service.requireTask(reviewer, "task", OWNER, OPERATOR, null);
        assertForbidden(() -> service.requireTask(reviewer, "task", OWNER, OPERATOR, Role.OWNER_TASK_ADMIN));
    }

    @Test
    void suppliedOrganizationAndRolesAreReplacedByCurrentDatabaseIdentity() {
        CurrentUser supplied = user(OWNER, Role.OWNER_TASK_ADMIN);
        CurrentUser current = user(OPERATOR, Role.OPERATOR_USER);
        when(authentication.authenticate(supplied.id(), supplied.tokenVersion())).thenReturn(current);
        CurrentUser verified = service.currentActor(supplied, false);
        assertEquals(current, verified);
        assertForbidden(() -> service.requireCreate(verified));
        verifyNoInteractions(identities);
    }

    @Test
    void aWriteLocksTheAccountBeforeRecheckingTheOriginalTokenVersion() {
        CurrentUser supplied = user(OWNER, Role.OWNER_TASK_ADMIN);
        when(authentication.authenticate(supplied.id(), supplied.tokenVersion()))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        BusinessException error = assertThrows(BusinessException.class, () -> service.currentActor(supplied, true));
        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode());
        var order = inOrder(identities, authentication);
        order.verify(identities).findUserByIdForUpdate(supplied.id());
        order.verify(authentication).authenticate(supplied.id(), supplied.tokenVersion());
        verifyNoInteractions(scopes);
    }

    @Test
    void taskMembershipTakesPrecedenceAndDoesNotRequireASharingGrant() {
        CurrentUser member = user(OPERATOR, Role.OPERATOR_USER);
        when(scopes.countMembership("task", member.id())).thenReturn(1);
        assertEquals(new ResourcePermissionService.VersionAccess(true, null, null),
                service.requireVersion(member, "version", "task", OWNER, OPERATOR, true));
        verify(scopes, never()).lockGrant(anyString(), anyString());
        verify(scopes, never()).now();
    }

    @Test
    void anOrganizationAdministratorCanReadOnlyAnExplicitlyGrantedVersion() {
        CurrentUser admin = user(OWNER, Role.ORG_ADMIN);
        Instant deadline = Instant.parse("2026-10-25T10:05:00Z");
        when(scopes.grant("version", admin.id())).thenReturn(new TaskScopeMapper.GrantScope("grant", deadline));
        when(scopes.now()).thenReturn(deadline.minusMillis(1));
        assertEquals(new ResourcePermissionService.VersionAccess(false, "grant", deadline),
                service.requireVersion(admin, "version", "task", OWNER, OPERATOR, false));
        assertForbidden(() -> service.requireVersion(admin, "other-version", "task", OWNER, OPERATOR, false));
        assertForbidden(() -> service.requireTask(admin, "task", OWNER, OPERATOR, null));
        verify(scopes, never()).countMembership(anyString(), anyString());
    }

    @Test
    void aWriteUsesTheLockedGrantAndChecksDatabaseTimeAfterTheLockWait() {
        CurrentUser outsider = user(OPERATOR, Role.OPERATOR_USER);
        Instant deadline = Instant.parse("2026-10-25T10:05:00Z");
        when(scopes.lockGrant("version", outsider.id())).thenReturn(new TaskScopeMapper.GrantScope("grant", deadline));
        when(scopes.now()).thenReturn(deadline.minusSeconds(1));
        service.requireVersion(outsider, "version", "task", OWNER, OPERATOR, true);
        var order = inOrder(scopes);
        order.verify(scopes).lockGrant("version", outsider.id());
        order.verify(scopes).now();
        verify(scopes, never()).grant(anyString(), anyString());
    }

    @Test
    void aGrantThatExpiresDuringTheLockWaitIsRejected() {
        CurrentUser outsider = user(OPERATOR, Role.OPERATOR_USER);
        Instant deadline = Instant.parse("2026-10-25T10:05:00Z");
        when(scopes.lockGrant("version", outsider.id())).thenReturn(new TaskScopeMapper.GrantScope("grant", deadline));
        when(scopes.now()).thenReturn(deadline);
        assertForbidden(() -> service.requireVersion(outsider, "version", "task", OWNER, OPERATOR, true));
    }

    @Test
    void revocationRemovesTheGrantPathEvenIfTheCallerStillHasABusinessRole() {
        CurrentUser outsider = user(OPERATOR, Role.OPERATOR_USER);
        assertForbidden(() -> service.requireVersion(outsider, "version", "task", OWNER, OPERATOR, true));
        verify(scopes).lockGrant("version", outsider.id());
    }

    @Test
    void managingAndReviewingMembersCanReadTaskHistoryInTheirOwnOrganization() {
        for (Role role : List.of(Role.OWNER_TASK_ADMIN, Role.OWNER_REVIEWER, Role.OWNER_DATA_APPROVER)) {
            CurrentUser actor = user(OWNER, role);
            when(scopes.countMembership("task", actor.id())).thenReturn(1);
            service.requireTaskHistory(actor, "task", OWNER, OPERATOR);
        }
        CurrentUser operator = user(OPERATOR, Role.OPERATOR_TASK_ADMIN);
        when(scopes.countMembership("task", operator.id())).thenReturn(1);
        service.requireTaskHistory(operator, "task", OWNER, OPERATOR);
    }

    @Test
    void aTaskOperatorOrOrganizationAdministratorDoesNotReceiveTaskWideHistory() {
        for (CurrentUser actor : List.of(user(OPERATOR, Role.OPERATOR_USER),
                user(OPERATOR, Role.ORG_ADMIN), user(OWNER, Role.ORG_ADMIN))) {
            assertForbidden(() -> service.requireTaskHistory(actor, "task", OWNER, OPERATOR));
        }
        verifyNoInteractions(scopes);
    }

    @Test
    void aVersionGrantCannotReplaceTaskMembershipForHistory() {
        CurrentUser actor = user(OWNER, Role.OWNER_REVIEWER);
        when(scopes.grant("version", actor.id())).thenReturn(
                new TaskScopeMapper.GrantScope("grant", Instant.MAX));
        assertForbidden(() -> service.requireTaskHistory(actor, "task", OWNER, OPERATOR));
        verify(scopes, never()).grant(anyString(), anyString());
        verify(scopes, never()).lockGrant(anyString(), anyString());
    }

    @Test
    void losingMembershipOrUsingAnotherTasksOrganizationsRemovesHistoryAccess() {
        CurrentUser actor = user(OWNER, Role.OWNER_DATA_APPROVER);
        when(scopes.countMembership("task", actor.id())).thenReturn(1, 0);
        service.requireTaskHistory(actor, "task", OWNER, OPERATOR);
        assertForbidden(() -> service.requireTaskHistory(actor, "task", OWNER, OPERATOR));
        assertForbidden(() -> service.requireTaskHistory(actor, "task", OPERATOR, OPERATOR));
    }

    private static CurrentUser user(String orgId, Role role) {
        return new CurrentUser("10000000-0000-4000-8000-000000000099", orgId,
                "test-user", List.of(role), 4, 2);
    }

    private static void assertForbidden(Runnable action) {
        BusinessException error = assertThrows(BusinessException.class, action::run);
        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }
}
