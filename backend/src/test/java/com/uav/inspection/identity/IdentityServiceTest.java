package com.uav.inspection.identity;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.IdentityMapper.UserRow;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class IdentityServiceTest {
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final String ACTOR = "10000000-0000-4000-8000-000000000001";
    private static final String TARGET = "10000000-0000-4000-8000-000000000002";
    private static final long ORIGINAL_TOKEN_VERSION = 7;
    private static final long EXPECTED_LOCK_VERSION = 10;

    private final IdentityMapper identities = mock(IdentityMapper.class);
    private final AuditMapper audit = mock(AuditMapper.class);
    private final IdentityService service = new IdentityService(identities, audit);
    private final CurrentUser actor = new CurrentUser(ACTOR, OWNER, "owner-admin",
            List.of(Role.ORG_ADMIN), ORIGINAL_TOKEN_VERSION, 3);

    @Test
    void adminRoleRevokedAfterAuthenticationCannotAuthorizeAnotherRoleChange() {
        when(identities.lockUsers(ACTOR, TARGET)).thenReturn(List.of(
                row(ACTOR, OWNER, "[\"OWNER_TASK_ADMIN\"]", ORIGINAL_TOKEN_VERSION, 3), target()));

        assertCode(ErrorCode.FORBIDDEN, () -> changeRoles(service));

        verifyNoRoleWriteOrAudit();
    }

    @Test
    void tokenInvalidatedBeforeTheRowsAreLockedIsRejected() {
        when(identities.lockUsers(ACTOR, TARGET)).thenReturn(List.of(
                row(ACTOR, OWNER, "[\"ORG_ADMIN\"]", ORIGINAL_TOKEN_VERSION + 1, 4), target()));

        assertCode(ErrorCode.UNAUTHORIZED, () -> changeRoles(service));

        verifyNoRoleWriteOrAudit();
    }

    @Test
    void administratorCannotChangeAnotherOrganizationsUser() {
        when(identities.lockUsers(ACTOR, TARGET)).thenReturn(List.of(admin(),
                row(TARGET, OPERATOR, "[\"OPERATOR_USER\"]", 0, EXPECTED_LOCK_VERSION)));

        assertCode(ErrorCode.FORBIDDEN, () -> changeRoles(service));

        verifyNoRoleWriteOrAudit();
    }

    @Test
    void staleExpectedLockVersionDoesNotOverwriteAConcurrentChange() {
        when(identities.lockUsers(ACTOR, TARGET)).thenReturn(List.of(admin(),
                row(TARGET, OWNER, "[\"OWNER_REVIEWER\"]", 1, EXPECTED_LOCK_VERSION + 1)));

        assertCode(ErrorCode.VERSION_CONFLICT, () -> changeRoles(service));

        verifyNoRoleWriteOrAudit();
    }

    @Test
    void failedRoleCompareAndSetRollsBackAndDoesNotAppendASuccessAudit() {
        when(identities.lockUsers(ACTOR, TARGET)).thenReturn(List.of(admin(), target()));
        when(identities.updateRoles(eq(TARGET), eq(OWNER), anyString(), eq(EXPECTED_LOCK_VERSION)))
                .thenReturn(0);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(transaction);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(interceptor);
        IdentityService transactional = (IdentityService) factory.getProxy();

        assertCode(ErrorCode.VERSION_CONFLICT, () -> changeRoles(transactional));

        verify(identities).updateRoles(eq(TARGET), eq(OWNER), anyString(), eq(EXPECTED_LOCK_VERSION));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        verifyNoInteractions(audit);
    }

    @Test
    void delayedLogoutUsesTheRequestsOriginalVersionAndDoesNotAuditANoOp() {
        when(identities.logoutVersion(ACTOR, ORIGINAL_TOKEN_VERSION)).thenReturn(0);

        service.logout(actor);

        verify(identities).logoutVersion(ACTOR, ORIGINAL_TOKEN_VERSION);
        // Re-reading and using a newer token version here could revoke a token issued after another logout.
        verifyNoMoreInteractions(identities);
        verifyNoInteractions(audit);
    }

    private void changeRoles(IdentityService targetService) {
        targetService.changeRoles(actor, TARGET, List.of(Role.OWNER_DATA_APPROVER), EXPECTED_LOCK_VERSION);
    }

    private void verifyNoRoleWriteOrAudit() {
        verify(identities, never()).updateRoles(anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(audit);
    }

    private static void assertCode(ErrorCode expected, Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        assertEquals(expected, exception.errorCode());
    }

    private static UserRow admin() {
        return row(ACTOR, OWNER, "[\"ORG_ADMIN\"]", ORIGINAL_TOKEN_VERSION, 3);
    }

    private static UserRow target() {
        return row(TARGET, OWNER, "[\"OWNER_REVIEWER\"]", 0, EXPECTED_LOCK_VERSION);
    }

    private static UserRow row(String id, String orgId, String rolesJson, long tokenVersion, long lockVersion) {
        return new UserRow(id, orgId, "fixture", "unused-password-hash", rolesJson,
                true, tokenVersion, lockVersion, Instant.EPOCH, Instant.EPOCH);
    }
}
