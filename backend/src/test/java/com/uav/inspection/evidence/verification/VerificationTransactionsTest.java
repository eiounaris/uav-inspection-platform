package com.uav.inspection.evidence.verification;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.verification.VerificationModels.Computed;
import com.uav.inspection.evidence.verification.persistence.VerificationMapper;
import com.uav.inspection.evidence.verification.persistence.VerificationMapper.VersionRow;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationTransactionsTest {
    private static final String VERSION="80000000-0000-4000-8000-000000000001";
    private static final CurrentUser ACTOR=new CurrentUser("user",ResourcePermissionService.OWNER,"owner",List.of(Role.OWNER_REVIEWER),0,0);
    private static final VersionRow ROW=new VersionRow(VERSION,"evidence",3,"task","private/object",ResourcePermissionService.OPERATOR,
            ResourcePermissionService.OWNER,ResourcePermissionService.OPERATOR);
    private final VerificationMapper mapper=mock(VerificationMapper.class);
    private final ResourcePermissionService permissions=mock(ResourcePermissionService.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final RecordingManager manager=new RecordingManager();
    private final Computed result=new Computed("a".repeat(64),"a".repeat(64),16L,"f".repeat(64),"MATCH","CONTENT_MATCH");

    @BeforeEach
    void configure() {
        when(mapper.version(VERSION)).thenReturn(ROW);
        when(mapper.lockTask("task")).thenReturn("task");
        when(permissions.currentActor(ACTOR,true)).thenAnswer(call->{ assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return ACTOR; });
        when(mapper.insert(any())).thenReturn(1);
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test
    void savingRechecksCurrentPermissionsAfterLockingTheParentAndBeforeInsertingAnything() {
        transactions().save(ACTOR,VERSION,"STORED",result);
        var order=inOrder(mapper,permissions,audit);
        order.verify(mapper).version(VERSION);
        order.verify(mapper).lockTask("task");
        order.verify(permissions).currentActor(ACTOR,true);
        order.verify(mapper).version(VERSION);
        order.verify(permissions).requireTask(ACTOR,"task",ROW.ownerOrgId(),ROW.operatorOrgId(),null);
        order.verify(mapper).insert(any());
        order.verify(audit).insertResourceSuccess(anyString(),eq(ACTOR.id()),eq(ACTOR.orgId()),eq("FILE_VERIFICATION_COMPLETED"),
                eq("EVIDENCE_VERSION"),eq(VERSION),anyString());
        assertEquals(1,manager.commits);
    }

    @Test
    void membershipRevokedDuringIoPreventsTheRecordAndItsAuditFromBeingWritten() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(permissions).requireTask(ACTOR,"task",ROW.ownerOrgId(),ROW.operatorOrgId(),null);
        BusinessException failure=assertThrows(BusinessException.class,()->transactions().save(ACTOR,VERSION,"COPY",result));
        assertEquals(ErrorCode.FORBIDDEN,failure.errorCode());
        verify(mapper,never()).insert(any());
        verifyNoInteractions(audit);
        assertEquals(1,manager.rollbacks);
    }

    @Test
    void auditFailureRollsBackTheSameTransactionAndDoesNotReturnARecord() {
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalStateException("Simulated audit persistence failure"));
        assertThrows(IllegalStateException.class,()->transactions().save(ACTOR,VERSION,"STORED",result));
        verify(mapper).insert(any());
        verify(mapper,never()).record(anyString());
        assertEquals(0,manager.commits);
        assertEquals(1,manager.rollbacks);
    }

    @Test
    void historyRequiresCurrentPermissionsAndAppliesPaginationAfterTheCheck() {
        when(mapper.count(VERSION)).thenReturn(9L);
        when(mapper.page(VERSION,3,3)).thenReturn(List.of());
        var page=transactions().history(ACTOR,VERSION,2,3);
        assertEquals(9,page.total());
        var order=inOrder(permissions,mapper);
        order.verify(permissions).requireTask(ACTOR,"task",ROW.ownerOrgId(),ROW.operatorOrgId(),null);
        order.verify(mapper).count(VERSION);
        order.verify(mapper).page(VERSION,3,3);
    }

    private VerificationTransactions transactions() {
        var proxy=new ProxyFactory(new VerificationTransactions(mapper,permissions,audit));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (VerificationTransactions)proxy.getProxy();
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        private int commits,rollbacks;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
