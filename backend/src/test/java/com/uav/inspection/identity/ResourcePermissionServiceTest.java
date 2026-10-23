package com.uav.inspection.identity;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    private static CurrentUser user(String orgId, Role role) {
        return new CurrentUser("10000000-0000-4000-8000-000000000099", orgId,
                "test-user", List.of(role), 4, 2);
    }

    private static void assertForbidden(Runnable action) {
        BusinessException error = assertThrows(BusinessException.class, action::run);
        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }
}
