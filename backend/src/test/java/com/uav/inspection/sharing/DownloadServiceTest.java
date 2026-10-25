package com.uav.inspection.sharing;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.storage.DownloadUrlSigner;
import com.uav.inspection.evidence.storage.DownloadUrlSigner.SignedDownload;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.ResourcePermissionService.VersionAccess;
import com.uav.inspection.identity.Role;
import com.uav.inspection.sharing.persistence.DownloadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DownloadServiceTest {
    private static final String VERSION="80000000-0000-4000-8000-000000000001",TASK="30000000-0000-4000-8000-000000000001";
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final CurrentUser ACTOR=new CurrentUser("10000000-0000-4000-8000-000000000005",OPERATOR,"operator-user",List.of(Role.OPERATOR_USER),0,0);
    private static final Instant NOW=Instant.parse("2026-10-25T00:00:00.250Z"),SIGNED_AT=Instant.parse("2026-10-25T00:00:00Z");
    private static final String KEY="evidence-files/synthetic-private-key",URL="http://127.0.0.1:9000/private/path?X-Amz-Signature=synthetic-secret";
    private final DownloadMapper mapper=mock(DownloadMapper.class);
    private final ResourcePermissionService permissions=mock(ResourcePermissionService.class);
    private final DownloadUrlSigner signer=mock(DownloadUrlSigner.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final RecordingManager manager=new RecordingManager();
    private final DownloadService service=transactional();

    @BeforeEach void setup() {
        when(mapper.version(VERSION)).thenReturn(new DownloadMapper.VersionRow(VERSION,TASK,KEY));
        when(mapper.lockTask(TASK)).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return new DownloadMapper.TaskRow(TASK,OWNER,OPERATOR);});
        when(permissions.currentActor(ACTOR,true)).thenReturn(ACTOR);
        when(permissions.requireVersion(ACTOR,VERSION,TASK,OWNER,OPERATOR,true)).thenReturn(new VersionAccess(true,null,null));
        when(mapper.now()).thenReturn(NOW);
        when(signer.sign(eq(KEY),eq(SIGNED_AT),anyLong())).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return new SignedDownload(URL,SIGNED_AT.plusSeconds(call.getArgument(2,Long.class)));
        });
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test void taskPathReauthenticatesUnderParentLockAndAuditsIssuanceWithoutStoringTheUrl() {
        var result=service.issue(ACTOR,VERSION);
        assertEquals(URL,result.url()); assertEquals(SIGNED_AT.plusSeconds(300),result.expiresAt());
        var order=inOrder(mapper,permissions,signer,audit);
        order.verify(mapper).lockTask(TASK); order.verify(permissions).currentActor(ACTOR,true);
        order.verify(permissions).requireVersion(ACTOR,VERSION,TASK,OWNER,OPERATOR,true);
        order.verify(mapper).now(); order.verify(signer).sign(KEY,SIGNED_AT,300);
        order.verify(mapper).now();
        order.verify(audit).insertResourceSuccess(anyString(),eq(ACTOR.id()),eq(OPERATOR),eq("URL_ISSUED"),eq("EVIDENCE_VERSION"),eq(VERSION),anyString());
        assertFalse(result.toString().contains(URL)); assertFalse(result.toString().contains("Signature"));
        assertEquals(1,manager.commits); assertEquals(0,manager.rollbacks);
    }

    @Test void sharedExpiryIsFlooredAndActualSignedExpiryNeverExceedsTheGrant() {
        Instant until=NOW.plusMillis(3999);
        when(permissions.requireVersion(ACTOR,VERSION,TASK,OWNER,OPERATOR,true)).thenReturn(new VersionAccess(false,"grant-id",until));
        var result=service.issue(ACTOR,VERSION);
        verify(signer).sign(KEY,SIGNED_AT,3);
        assertEquals(SIGNED_AT.plusSeconds(3),result.expiresAt()); assertFalse(result.expiresAt().isAfter(until));
    }

    @Test void aLongerGrantIsStillCappedAtFiveMinutes() {
        when(permissions.requireVersion(ACTOR,VERSION,TASK,OWNER,OPERATOR,true)).thenReturn(new VersionAccess(false,"grant-id",NOW.plusSeconds(700)));
        service.issue(ACTOR,VERSION); verify(signer).sign(KEY,SIGNED_AT,300);
    }

    @Test void lessThanOneSecondOfGrantLifetimeCannotIssueOrAuditAUrl() {
        when(permissions.requireVersion(ACTOR,VERSION,TASK,OWNER,OPERATOR,true)).thenReturn(new VersionAccess(false,"grant-id",NOW.plusMillis(999)));
        assertCode(ErrorCode.FORBIDDEN,()->service.issue(ACTOR,VERSION)); verifyNoInteractions(signer,audit);
    }

    @Test void waitingForTheGrantOrSigningPastExpiryCannotReturnANewCredential() {
        when(permissions.requireVersion(ACTOR,VERSION,TASK,OWNER,OPERATOR,true)).thenReturn(new VersionAccess(false,"grant-id",NOW.plusSeconds(2)));
        when(mapper.now()).thenReturn(NOW,SIGNED_AT.plusSeconds(2));
        assertCode(ErrorCode.FORBIDDEN,()->service.issue(ACTOR,VERSION)); verifyNoInteractions(audit);
        assertEquals(1,manager.rollbacks);
    }

    @Test void revokedOrOtherwiseMissingScopeIsRejectedBeforeSigning() {
        when(permissions.requireVersion(ACTOR,VERSION,TASK,OWNER,OPERATOR,true)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertCode(ErrorCode.FORBIDDEN,()->service.issue(ACTOR,VERSION)); verifyNoInteractions(signer,audit); verify(mapper,never()).now();
    }

    @Test void signerFailureAndAuditFailureDoNotCommitAnIssuance() {
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(signer).sign(KEY,SIGNED_AT,300);
        assertCode(ErrorCode.DEPENDENCY_UNAVAILABLE,()->service.issue(ACTOR,VERSION)); verifyNoInteractions(audit);
        doReturn(new SignedDownload(URL,SIGNED_AT.plusSeconds(300))).when(signer).sign(KEY,SIGNED_AT,300);
        var failure=new DataIntegrityViolationException("synthetic audit failure");
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenThrow(failure);
        assertSame(failure,assertThrows(DataIntegrityViolationException.class,()->service.issue(ACTOR,VERSION)));
        assertEquals(0,manager.commits); assertEquals(2,manager.rollbacks);
    }

    @Test void aSignerCannotExtendTheComputedLifetimeOrReturnInconsistentMetadata() {
        doReturn(new SignedDownload(URL,SIGNED_AT.plusSeconds(301))).when(signer).sign(KEY,SIGNED_AT,300);
        assertCode(ErrorCode.DEPENDENCY_UNAVAILABLE,()->service.issue(ACTOR,VERSION)); verifyNoInteractions(audit);
    }

    @Test void malformedOrUnknownVersionsDoNotRevealFileMetadata() {
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.issue(ACTOR,"invalid"));
        when(mapper.version(VERSION)).thenReturn(null);
        assertCode(ErrorCode.FORBIDDEN,()->service.issue(ACTOR,VERSION)); verifyNoInteractions(permissions,signer,audit);
    }

    private static void assertCode(ErrorCode code,Runnable action) {
        assertEquals(code,assertThrows(BusinessException.class,action::run).errorCode());
    }
    private DownloadService transactional() {
        var advice=new TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy=new ProxyFactory(new DownloadService(mapper,permissions,signer,audit));proxy.setProxyTargetClass(true);proxy.addAdvice(advice);
        return (DownloadService)proxy.getProxy();
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
