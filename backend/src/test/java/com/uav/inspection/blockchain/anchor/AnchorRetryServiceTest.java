package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.blockchain.anchor.AnchorRetryModels.Request;
import com.uav.inspection.blockchain.anchor.persistence.AnchorRetryMapper;
import com.uav.inspection.blockchain.anchor.persistence.AnchorRetryMapper.*;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.*;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import com.uav.inspection.messaging.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnchorRetryServiceTest {
    private static final String TASK="30000000-0000-4000-8000-000000000001";
    private static final String VERSION="70000000-0000-4000-8000-000000000001";
    private static final String SUBMISSION="80000000-0000-4000-8000-000000000001";
    private static final String EVENT="90000000-0000-4000-8000-000000000002";
    private static final String REQUEST="a0000000-0000-4000-8000-000000000001";
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final Instant NOW=Instant.parse("2026-10-26T02:00:00Z");
    private static final CurrentUser ADMIN=actor(OPERATOR,Role.OPERATOR_TASK_ADMIN);
    private final AnchorRetryMapper mapper=mock(AnchorRetryMapper.class);
    private final IdentityMapper identities=mock(IdentityMapper.class);
    private final IdentityService authentication=mock(IdentityService.class);
    private final TaskScopeMapper memberships=mock(TaskScopeMapper.class);
    private final OutboxWriter outbox=mock(OutboxWriter.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final RecordingManager transactions=new RecordingManager();
    private final AtomicReference<RequestRow> saved=new AtomicReference<>();
    private final AnchorRetryService service=service();

    @BeforeEach
    void configure() {
        when(mapper.taskIdForVersion(VERSION)).thenReturn(TASK);
        when(mapper.lockTask(TASK)).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return new TaskRow(TASK,OWNER,OPERATOR,"RUNNING");
        });
        when(authentication.authenticate(anyString(),anyLong())).thenReturn(ADMIN);
        when(memberships.countMembership(TASK,ADMIN.id())).thenReturn(1);
        when(mapper.lockVersion(VERSION)).thenReturn(new VersionRow(VERSION,TASK));
        when(mapper.lockSubmission(VERSION)).thenReturn(submission("UNKNOWN",true,null,null));
        when(mapper.latestDispatch(SUBMISSION)).thenReturn(new DispatchRow(UUID.randomUUID().toString(),0,"DONE"));
        when(mapper.latestAttemptNo(SUBMISSION)).thenReturn(11);
        when(mapper.schedule(SUBMISSION)).thenReturn(1);
        when(outbox.enqueue(eq("EVIDENCE_ANCHOR_REQUESTED"),eq(SUBMISSION),anyString(),eq(1))).thenReturn(EVENT);
        when(mapper.insert(any())).thenAnswer(call->{
            RequestRow r=call.getArgument(0);
            saved.set(new RequestRow(r.id(),r.submissionId(),r.requestedBy(),r.clientRequestId(),r.requestHash(),
                    r.reason(),r.dispatchNo(),r.baselineAttemptNo(),r.eventId(),NOW));
            return 1;
        });
        when(mapper.find(anyString())).thenAnswer(call->saved.get());
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test
    void acceptanceReauthenticatesUnderTaskLockAndAtomicallyStoresIntentEventAndAudit() {
        var receipt=service.request(ADMIN,VERSION,new Request(REQUEST,"  重新核对原交易  "));
        assertEquals(NOW,receipt.acceptedAt());assertEquals(EVENT,receipt.eventId());assertEquals(1,receipt.dispatchNo());
        assertEquals("重新核对原交易",saved.get().reason());assertEquals(11,saved.get().baselineAttemptNo());
        assertTrue(saved.get().requestHash().matches("[0-9a-f]{64}"));
        var order=inOrder(mapper,identities,authentication,memberships,outbox,audit);
        order.verify(mapper).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(ADMIN.id());
        order.verify(authentication).authenticate(ADMIN.id(),0);
        order.verify(memberships).countMembership(TASK,ADMIN.id());
        order.verify(mapper).lockVersion(VERSION);order.verify(mapper).lockSubmission(VERSION);
        order.verify(mapper).schedule(SUBMISSION);
        order.verify(outbox).enqueue(eq("EVIDENCE_ANCHOR_REQUESTED"),eq(SUBMISSION),anyString(),eq(1));
        order.verify(mapper).insert(any());
        order.verify(audit).insertResourceSuccess(anyString(),eq(ADMIN.id()),eq(OPERATOR),
                eq("EVIDENCE_ANCHOR_RECHECK_REQUESTED"),eq("CHAIN_SUBMISSION"),eq(SUBMISSION),anyString());
        assertEquals(1,transactions.commits);assertEquals(0,transactions.rollbacks);
    }

    @Test
    void anIdenticalAcceptedRequestReplaysItsReceiptEvenAfterConfirmationWithoutNewWrites() {
        var initial=service.request(ADMIN,VERSION,new Request(REQUEST,"检查"));
        when(mapper.request(SUBMISSION,ADMIN.id(),REQUEST)).thenReturn(saved.get());
        when(mapper.lockSubmission(VERSION)).thenReturn(submission("CONFIRMED",false,null,null));
        doReturn(new TaskRow(TASK,OWNER,OPERATOR,"COMPLETED")).when(mapper).lockTask(TASK);
        var repeated=service.request(ADMIN,VERSION,new Request(REQUEST," 检查 "));
        assertEquals(initial,repeated);
        verify(mapper,times(1)).schedule(SUBMISSION);verify(mapper,times(1)).insert(any());
        verify(outbox,times(1)).enqueue(anyString(),anyString(),anyString(),anyInt());
        verify(audit,times(1)).insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void anIdempotencyKeyCannotBeReusedWithAnotherReason() {
        service.request(ADMIN,VERSION,new Request(REQUEST,"检查"));
        when(mapper.request(SUBMISSION,ADMIN.id(),REQUEST)).thenReturn(saved.get());
        code(ErrorCode.IDEMPOTENCY_CONFLICT,()->service.request(ADMIN,VERSION,new Request(REQUEST,"重新提交")));
        verify(mapper,times(1)).insert(any());
    }

    @Test
    void replayStillRequiresCurrentMembershipAndDoesNotFallBackToAVersionGrant() {
        service.request(ADMIN,VERSION,new Request(REQUEST,"检查"));
        when(mapper.request(SUBMISSION,ADMIN.id(),REQUEST)).thenReturn(saved.get());
        when(memberships.countMembership(TASK,ADMIN.id())).thenReturn(0);
        code(ErrorCode.FORBIDDEN,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        verify(mapper,times(1)).request(SUBMISSION,ADMIN.id(),REQUEST);
        verify(memberships,never()).grant(anyString(),anyString());
    }

    @Test
    void ownerOperatorUserAndOrgAdminOnlyRolesCannotRequestARecheck() {
        for(CurrentUser unauthorized:List.of(actor(OWNER,Role.OWNER_TASK_ADMIN),
                actor(OPERATOR,Role.OPERATOR_USER),actor(OPERATOR,Role.ORG_ADMIN))) {
            when(authentication.authenticate(anyString(),anyLong())).thenReturn(unauthorized);
            code(ErrorCode.FORBIDDEN,()->service.request(unauthorized,VERSION,new Request(REQUEST,"检查")));
        }
        verify(mapper,never()).lockVersion(anyString());verifyNoInteractions(outbox,audit);
    }

    @Test
    void currentIdentityFailurePreventsStateAndRequestHistoryLookups() {
        when(authentication.authenticate(ADMIN.id(),0)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        code(ErrorCode.UNAUTHORIZED,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        verify(mapper,never()).lockVersion(anyString());verify(mapper,never()).lockSubmission(anyString());
        verify(mapper,never()).request(anyString(),anyString(),anyString());verifyNoInteractions(outbox,audit);
    }

    @Test
    void unknownVersionAndNonmemberVersionBothProduceForbiddenBeforeState() {
        when(mapper.taskIdForVersion(VERSION)).thenReturn(null);
        code(ErrorCode.FORBIDDEN,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        when(mapper.taskIdForVersion(VERSION)).thenReturn(TASK);
        when(memberships.countMembership(TASK,ADMIN.id())).thenReturn(0);
        code(ErrorCode.FORBIDDEN,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        verify(mapper,never()).lockSubmission(anyString());verifyNoInteractions(outbox,audit);
    }

    @Test
    void freshRequestsCannotChangeConfirmedPermanentFailureOrCompletedTasks() {
        for(SubmissionRow terminal:List.of(submission("CONFIRMED",false,null,null),submission("FAILED",false,null,null))) {
            when(mapper.lockSubmission(VERSION)).thenReturn(terminal);
            code(ErrorCode.VERSION_CONFLICT,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        }
        when(mapper.lockSubmission(VERSION)).thenReturn(submission("UNKNOWN",true,null,null));
        doReturn(new TaskRow(TASK,OWNER,OPERATOR,"COMPLETED")).when(mapper).lockTask(TASK);
        code(ErrorCode.TASK_COMPLETED,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        verify(mapper,never()).schedule(anyString());verifyNoInteractions(outbox,audit);
    }

    @Test
    void anActiveOrExpiredLeaseCannotBeClearedByManualRequests() {
        for(Instant expiry:List.of(NOW.plusSeconds(120),NOW.minusSeconds(1))) {
            when(mapper.lockSubmission(VERSION)).thenReturn(submission("UNKNOWN",true,UUID.randomUUID().toString(),expiry));
            code(ErrorCode.VERSION_CONFLICT,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        }
        verify(mapper,never()).schedule(anyString());verifyNoInteractions(outbox,audit);
    }

    @Test
    void anUnprocessedLatestDispatchCannotBeReplacedByAnotherButtonClick() {
        for(String status:List.of("PROCESSING","MISSING")) {
            when(mapper.latestDispatch(SUBMISSION)).thenReturn(new DispatchRow(EVENT,1,status.equals("MISSING")?null:status));
            code(ErrorCode.VERSION_CONFLICT,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        }
        verify(mapper,never()).schedule(anyString());verifyNoInteractions(outbox,audit);
    }

    @Test
    void exhaustedRecoverableFailureStartsANewDispatchWithoutResettingHistory() {
        when(mapper.lockSubmission(VERSION)).thenReturn(submission("FAILED",true,null,null));
        when(mapper.latestDispatch(SUBMISSION)).thenReturn(new DispatchRow(EVENT,3,"DONE"));
        when(outbox.enqueue(eq("EVIDENCE_ANCHOR_REQUESTED"),eq(SUBMISSION),anyString(),eq(4))).thenReturn(EVENT);
        var receipt=service.request(ADMIN,VERSION,new Request(REQUEST,"修复后重试"));
        assertEquals(4,receipt.dispatchNo());assertEquals(11,saved.get().baselineAttemptNo());
        verify(mapper).schedule(SUBMISSION);verify(mapper).latestAttemptNo(SUBMISSION);
    }

    @Test
    void failedConditionalSchedulingDoesNotProduceAnEventOrAcceptance() {
        when(mapper.schedule(SUBMISSION)).thenReturn(0);
        code(ErrorCode.VERSION_CONFLICT,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        verifyNoInteractions(outbox,audit);verify(mapper,never()).insert(any());
        assertEquals(1,transactions.rollbacks);
    }

    @Test
    void anAuditFailureRollsBackTheSchedulingEventAndReceiptTransaction() {
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));
        assertThrows(DataAccessResourceFailureException.class,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        verify(mapper).schedule(SUBMISSION);verify(mapper).insert(any());verify(mapper,never()).find(anyString());
        assertEquals(0,transactions.commits);assertEquals(1,transactions.rollbacks);
    }

    @Test
    void anOutboxFailureRollsBackSchedulingWithoutCreatingAReceiptOrAudit() {
        when(outbox.enqueue(anyString(),anyString(),anyString(),anyInt()))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));
        assertThrows(DataAccessResourceFailureException.class,()->service.request(ADMIN,VERSION,new Request(REQUEST,"检查")));
        verify(mapper).schedule(SUBMISSION);verify(mapper,never()).insert(any());verifyNoInteractions(audit);
        assertEquals(0,transactions.commits);assertEquals(1,transactions.rollbacks);
    }

    @Test
    void invalidIdsAndBlankOrOversizedReasonsAreRejectedBeforeAnyLookup() {
        code(ErrorCode.INVALID_ARGUMENT,()->service.request(ADMIN,"bad",new Request(REQUEST,"检查")));
        code(ErrorCode.INVALID_ARGUMENT,()->service.request(ADMIN,VERSION,new Request("bad","检查")));
        for(String reason:List.of(""," \t\u3000\u00a0","长".repeat(1001)))
            code(ErrorCode.INVALID_ARGUMENT,()->service.request(ADMIN,VERSION,new Request(REQUEST,reason)));
        verifyNoInteractions(mapper,identities,authentication,memberships,outbox,audit);
    }

    private AnchorRetryService service() {
        var permissions=new ResourcePermissionService(identities,authentication,memberships);
        var proxy=new ProxyFactory(new AnchorRetryService(mapper,permissions,outbox,audit));
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (AnchorRetryService)proxy.getProxy();
    }
    private static CurrentUser actor(String org,Role role) {
        return new CurrentUser("10000000-0000-4000-8000-000000000004",org,"test",List.of(role),0,0);
    }
    private static SubmissionRow submission(String status,boolean retryable,String token,Instant expiry) {
        return new SubmissionRow(SUBMISSION,VERSION,status,retryable,token,expiry);
    }
    private static void code(ErrorCode expected,org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected,assertThrows(BusinessException.class,action).errorCode());
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
