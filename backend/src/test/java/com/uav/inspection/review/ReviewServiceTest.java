package com.uav.inspection.review;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import com.uav.inspection.review.ReviewModels.*;
import com.uav.inspection.review.persistence.ReviewMapper;
import com.uav.inspection.review.persistence.ReviewMapper.TaskRow;
import com.uav.inspection.review.persistence.ReviewMapper.VersionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewServiceTest {
    private static final String TASK="30000000-0000-4000-8000-000000000001";
    private static final String VERSION="80000000-0000-4000-8000-000000000001";
    private static final String OTHER_VERSION="80000000-0000-4000-8000-000000000002";
    private static final String SUBTASK="40000000-0000-4000-8000-000000000001";
    private static final String REQUIREMENT="60000000-0000-4000-8000-000000000001";
    private static final String EXECUTION="50000000-0000-4000-8000-000000000001";
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final Instant NOW=Instant.parse("2026-10-24T11:22:33.456Z");
    private static final CurrentUser REVIEWER=actor("10000000-0000-4000-8000-000000000002",OWNER,Role.OWNER_REVIEWER);
    private static final CurrentUser OPERATOR_USER=actor("10000000-0000-4000-8000-000000000006",OPERATOR,Role.OPERATOR_USER);
    private static final CurrentUser OWNER_ADMIN=actor("10000000-0000-4000-8000-000000000001",OWNER,Role.OWNER_TASK_ADMIN);
    private static final CurrentUser OPERATOR_ADMIN=actor("10000000-0000-4000-8000-000000000004",OPERATOR,Role.OPERATOR_TASK_ADMIN);
    private final ReviewMapper mapper=mock(ReviewMapper.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final IdentityMapper identities=mock(IdentityMapper.class);
    private final IdentityService authentication=mock(IdentityService.class);
    private final TaskScopeMapper memberships=mock(TaskScopeMapper.class);
    private final Map<String,CurrentUser> currentUsers=new HashMap<>();
    private final AtomicReference<IssueView> createdIssue=new AtomicReference<>();
    private final AtomicReference<ReviewView> createdReview=new AtomicReference<>();
    private final RecordingManager manager=new RecordingManager();
    private final ReviewService service=transactional();

    @BeforeEach
    void configure() {
        for(CurrentUser actor:List.of(REVIEWER,OPERATOR_USER,OWNER_ADMIN,OPERATOR_ADMIN)) currentUsers.put(actor.id(),actor);
        when(authentication.authenticate(anyString(),anyLong())).thenAnswer(call->currentUsers.get(call.getArgument(0)));
        when(memberships.countMembership(eq(TASK),anyString())).thenReturn(1);
        when(mapper.taskIdForVersion(VERSION)).thenReturn(TASK);
        when(mapper.lockTask(TASK)).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return task("RUNNING");
        });
        when(mapper.lockVersion(VERSION)).thenReturn(version("PENDING"));
        when(mapper.decide(eq(VERSION),eq(TASK),anyString(),eq(7L))).thenReturn(1);
        when(mapper.reopenTask(TASK,3)).thenReturn(1);
        when(mapper.insertIssue(any())).thenAnswer(call->{createdIssue.set(call.getArgument(0)); return 1;});
        when(mapper.issue(anyString())).thenAnswer(call->{
            IssueView row=createdIssue.get();
            return new IssueView(row.id(),row.taskId(),row.versionId(),row.description(),row.createdBy(),NOW);
        });
        when(mapper.insertReview(any())).thenAnswer(call->{
            ReviewView row=call.getArgument(0);
            createdReview.set(new ReviewView(row.id(),row.versionId(),row.decision(),row.reason(),row.reviewerId(),NOW));
            return 1;
        });
        when(mapper.review(VERSION)).thenAnswer(call->createdReview.get());
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test
    void issueIsBoundToTheNamedVersionAndCurrentOperatorWithDatabaseTimestampAndAtomicAudit() {
        IssueView result=service.createIssue(OPERATOR_USER,new CreateIssueRequest(VERSION,"  电线绝缘层破损  "));

        assertEquals(TASK,result.taskId()); assertEquals(VERSION,result.versionId());
        assertEquals("电线绝缘层破损",result.description()); assertEquals(OPERATOR_USER.id(),result.createdBy());
        assertEquals(NOW,result.createdAt());
        var order=inOrder(mapper,identities,authentication,memberships,audit);
        order.verify(mapper).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(OPERATOR_USER.id());
        order.verify(authentication).authenticate(OPERATOR_USER.id(),0);
        order.verify(memberships).countMembership(TASK,OPERATOR_USER.id());
        order.verify(mapper).lockVersion(VERSION);
        order.verify(mapper).insertIssue(any());
        order.verify(audit).insertResourceSuccess(anyString(),eq(OPERATOR_USER.id()),eq(OPERATOR),eq("ISSUE_CREATED"),eq("ISSUE"),eq(result.id()),anyString());
        assertEquals(1,manager.commits); assertEquals(0,manager.rollbacks);
        verify(mapper,never()).decide(anyString(),anyString(),anyString(),anyLong());
    }

    @Test
    void administrativeRolesDoNotImplicitlyGrantIssueOrReviewBusinessPrivileges() {
        assertCode(ErrorCode.FORBIDDEN,()->service.createIssue(OPERATOR_ADMIN,new CreateIssueRequest(VERSION,"issue")));
        assertCode(ErrorCode.FORBIDDEN,()->service.createIssue(REVIEWER,new CreateIssueRequest(VERSION,"issue")));
        assertCode(ErrorCode.FORBIDDEN,()->service.review(OWNER_ADMIN,VERSION,approved()));
        assertCode(ErrorCode.FORBIDDEN,()->service.review(OPERATOR_USER,VERSION,approved()));
        verify(mapper,never()).lockVersion(anyString());
        verify(mapper,never()).insertIssue(any());
        verify(mapper,never()).decide(anyString(),anyString(),anyString(),anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void currentMembershipAndCurrentRolesAreRevalidatedBeforeAnyVersionOrHistoryRead() {
        when(memberships.countMembership(TASK,REVIEWER.id())).thenReturn(0);
        assertCode(ErrorCode.FORBIDDEN,()->service.review(REVIEWER,VERSION,approved()));
        assertCode(ErrorCode.FORBIDDEN,()->service.review(REVIEWER,VERSION));
        assertCode(ErrorCode.FORBIDDEN,()->service.issues(REVIEWER,VERSION,1,20));
        currentUsers.put(OPERATOR_USER.id(),actor(OPERATOR_USER.id(),OPERATOR,Role.OPERATOR_TASK_ADMIN));
        assertCode(ErrorCode.FORBIDDEN,()->service.createIssue(OPERATOR_USER,new CreateIssueRequest(VERSION,"issue")));
        verify(mapper,never()).lockVersion(anyString());
        verify(mapper,never()).review(anyString());
        verify(mapper,never()).countIssues(anyString());
        verify(mapper,never()).issues(anyString(),anyInt(),anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void completedTaskBlocksBothWritesButKeepsExistingHistoryReadable() {
        doReturn(task("COMPLETED")).when(mapper).lockTask(TASK);
        assertCode(ErrorCode.TASK_COMPLETED,()->service.createIssue(OPERATOR_USER,new CreateIssueRequest(VERSION,"issue")));
        assertCode(ErrorCode.TASK_COMPLETED,()->service.review(REVIEWER,VERSION,approved()));

        assertEquals("PENDING",service.review(REVIEWER,VERSION).reviewStatus());
        when(mapper.issues(VERSION,20,0)).thenReturn(List.of());
        assertTrue(service.issues(OPERATOR_USER,VERSION,1,20).items().isEmpty());
        verify(mapper,never()).insertIssue(any());
        verify(mapper,never()).decide(anyString(),anyString(),anyString(),anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void unicodeBlankAndOversizedIssueDescriptionsAreRejectedBeforeDatabaseAccess() {
        for(String invalid:List.of(""," ","\t\r\n","\u00a0\u2007\u202f","\u0085","字".repeat(4001))) {
            assertCode(ErrorCode.INVALID_ARGUMENT,()->service.createIssue(OPERATOR_USER,new CreateIssueRequest(VERSION,invalid)));
        }
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.createIssue(OPERATOR_USER,new CreateIssueRequest(VERSION,null)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.createIssue(OPERATOR_USER,null));
        verifyNoInteractions(mapper,identities,authentication,memberships,audit);
    }

    @Test
    void issueLengthBoundaryAllowsChineseAndValidSupplementaryUnicodeWithoutSplittingCharacters() {
        for(String allowed:List.of("字".repeat(4000),"😀".repeat(2000))) {
            IssueView result=service.createIssue(OPERATOR_USER,new CreateIssueRequest(VERSION,allowed));
            assertEquals(allowed,result.description());
        }
        assertEquals(2,manager.commits);
    }

    @Test
    void approvalFormsOneVersionConclusionWithoutWaitingForTheLedgerOrReopeningTheTask() {
        doReturn(task("WAITING_ACCEPTANCE")).when(mapper).lockTask(TASK);

        ReviewState result=service.review(REVIEWER,VERSION,approved());

        assertEquals(VERSION,result.versionId()); assertEquals("APPROVED",result.reviewStatus());
        assertEquals(REVIEWER.id(),result.review().reviewerId()); assertEquals(NOW,result.review().createdAt());
        assertNull(result.review().reason());
        var order=inOrder(mapper,identities,authentication,audit);
        order.verify(mapper).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(REVIEWER.id());
        order.verify(authentication).authenticate(REVIEWER.id(),0);
        order.verify(mapper).lockVersion(VERSION);
        order.verify(mapper).decide(VERSION,TASK,"APPROVED",7);
        order.verify(mapper).insertReview(any());
        order.verify(audit).insertResourceSuccess(anyString(),eq(REVIEWER.id()),eq(OWNER),eq("EVIDENCE_REVIEWED"),eq("EVIDENCE_VERSION"),eq(VERSION),anyString());
        verify(mapper,never()).currentVersionId(anyString(),anyString(),anyString());
        verify(mapper,never()).reopenTask(anyString(),anyLong());
        assertEquals(1,manager.commits); assertEquals(0,manager.rollbacks);
    }

    @Test
    void returningTheCurrentLatestVersionReopensOnlyTheWaitingTaskInTheSameTransaction() {
        doReturn(task("WAITING_ACCEPTANCE")).when(mapper).lockTask(TASK);
        when(mapper.currentVersionId(TASK,SUBTASK,REQUIREMENT)).thenReturn(VERSION);

        ReviewState result=service.review(REVIEWER,VERSION,new ReviewRequest("RETURNED","  缺少塔身全景  ","PENDING"));

        assertEquals("RETURNED",result.reviewStatus()); assertEquals("缺少塔身全景",result.review().reason());
        var order=inOrder(mapper,audit);
        order.verify(mapper).decide(VERSION,TASK,"RETURNED",7);
        order.verify(mapper).insertReview(any());
        order.verify(mapper).currentVersionId(TASK,SUBTASK,REQUIREMENT);
        order.verify(mapper).reopenTask(TASK,3);
        order.verify(audit).insertResourceSuccess(anyString(),anyString(),anyString(),eq("EVIDENCE_REVIEWED"),anyString(),eq(VERSION),anyString());
        assertEquals(1,manager.commits);
    }

    @Test
    void returningAnOlderVersionDoesNotReopenAWaitingTaskWithANewerCurrentVersion() {
        doReturn(task("WAITING_ACCEPTANCE")).when(mapper).lockTask(TASK);
        when(mapper.currentVersionId(TASK,SUBTASK,REQUIREMENT)).thenReturn(OTHER_VERSION);
        assertEquals("RETURNED",service.review(REVIEWER,VERSION,new ReviewRequest("RETURNED","historical finding","PENDING")).reviewStatus());
        verify(mapper,never()).reopenTask(anyString(),anyLong());
    }

    @Test
    void returningAClosedHistoricalBatchDoesNotReopenATaskEvenIfThatVersionNumberIsHigher() {
        doReturn(task("WAITING_ACCEPTANCE")).when(mapper).lockTask(TASK);
        when(mapper.lockVersion(VERSION)).thenReturn(new VersionRow(VERSION,TASK,SUBTASK,REQUIREMENT,"closed-execution",99,"PENDING",7));
        when(mapper.currentVersionId(TASK,SUBTASK,REQUIREMENT)).thenReturn(OTHER_VERSION);
        service.review(REVIEWER,VERSION,new ReviewRequest("RETURNED","late historical version","PENDING"));
        verify(mapper).insertReview(any());
        verify(mapper,never()).reopenTask(anyString(),anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings={"APPROVED","RETURNED"})
    void anExistingConclusionCannotBeOverwrittenEvenByTheOriginalReviewer(String status) {
        when(mapper.lockVersion(VERSION)).thenReturn(version(status));
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.review(REVIEWER,VERSION,approved()));
        verify(mapper,never()).decide(anyString(),anyString(),anyString(),anyLong());
        verify(mapper,never()).insertReview(any());
        verifyNoInteractions(audit);
    }

    @Test
    void requestDecisionExpectedStatusAndReasonAreValidatedWithoutImplicitConversions() {
        for(ReviewRequest request:List.of(new ReviewRequest(null,null,"PENDING"),new ReviewRequest("approved",null,"PENDING"),
                new ReviewRequest("APPROVED",null,"APPROVED"),new ReviewRequest("RETURNED",null,"PENDING"),
                new ReviewRequest("RETURNED","\u0085\u00a0","PENDING"),new ReviewRequest("APPROVED"," ","PENDING"),
                new ReviewRequest("RETURNED","字".repeat(1001),"PENDING"))) {
            assertCode(ErrorCode.INVALID_ARGUMENT,()->service.review(REVIEWER,VERSION,request));
        }
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.review(REVIEWER,VERSION,null));
        verifyNoInteractions(mapper,identities,authentication,memberships,audit);
    }

    @Test
    void aReasonAtItsChineseLengthLimitIsStoredUnchanged() {
        String reason="字".repeat(1000);
        assertEquals(reason,service.review(REVIEWER,VERSION,new ReviewRequest("APPROVED",reason,"PENDING")).review().reason());
    }

    @Test
    void aFailedVersionCompareAndSetCannotCreateAConclusionOrAudit() {
        when(mapper.decide(VERSION,TASK,"APPROVED",7)).thenReturn(0);
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.review(REVIEWER,VERSION,approved()));
        verify(mapper,never()).insertReview(any()); verifyNoInteractions(audit);
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void duplicateConclusionConstraintRollsBackTheVersionChangeAndReturnsAConflict() {
        doThrow(new DuplicateKeyException("review already exists")).when(mapper).insertReview(any());
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.review(REVIEWER,VERSION,approved()));
        verify(mapper).decide(VERSION,TASK,"APPROVED",7);
        verifyNoInteractions(audit);
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void reviewAndTaskReopeningRollBackTogetherIfAuditPersistenceFails() {
        doReturn(task("WAITING_ACCEPTANCE")).when(mapper).lockTask(TASK);
        when(mapper.currentVersionId(TASK,SUBTASK,REQUIREMENT)).thenReturn(VERSION);
        var failure=new DataIntegrityViolationException("audit write failed");
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenThrow(failure);
        assertSame(failure,assertThrows(DataIntegrityViolationException.class,
                ()->service.review(REVIEWER,VERSION,new ReviewRequest("RETURNED","missing angle","PENDING"))));
        verify(mapper).insertReview(any()); verify(mapper).reopenTask(TASK,3);
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void issueAndItsAuditAlsoShareOneRollbackBoundary() {
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->service.createIssue(OPERATOR_USER,new CreateIssueRequest(VERSION,"issue")));
        verify(mapper).insertIssue(any()); verify(mapper,never()).issue(anyString());
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void issuePaginationChecksCurrentPermissionAndUsesALongOffset() {
        when(mapper.countIssues(VERSION)).thenReturn(9L);
        when(mapper.issues(VERSION,100,214748364600L)).thenReturn(List.of());
        IssuePage result=service.issues(REVIEWER,VERSION,Integer.MAX_VALUE,100);
        assertEquals(9,result.total()); assertEquals(Integer.MAX_VALUE,result.page());
        var order=inOrder(mapper,identities,authentication,memberships);
        order.verify(mapper).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(REVIEWER.id());
        order.verify(authentication).authenticate(REVIEWER.id(),0);
        order.verify(memberships).countMembership(TASK,REVIEWER.id());
        order.verify(mapper).lockVersion(VERSION);
        order.verify(mapper).countIssues(VERSION);
        order.verify(mapper).issues(VERSION,100,214748364600L);
        verifyNoInteractions(audit);
    }

    @Test
    void aNewVersionStartsPendingWithNoInheritedConclusion() {
        ReviewState previous=service.review(REVIEWER,VERSION,approved());
        when(mapper.taskIdForVersion(OTHER_VERSION)).thenReturn(TASK);
        when(mapper.lockVersion(OTHER_VERSION)).thenReturn(new VersionRow(OTHER_VERSION,TASK,SUBTASK,REQUIREMENT,EXECUTION,3,"PENDING",0));

        ReviewState pending=service.review(OPERATOR_USER,OTHER_VERSION);

        assertEquals(OTHER_VERSION,pending.versionId()); assertEquals("PENDING",pending.reviewStatus()); assertNull(pending.review());
        assertEquals("APPROVED",previous.review().decision());
        assertEquals(VERSION,createdReview.get().versionId());
        verify(mapper).review(OTHER_VERSION);
        verify(mapper,times(1)).insertReview(any());
    }

    @Test
    void unknownResourcesAreForbiddenWhileBadIdsAndPaginationAreBadRequests() {
        assertCode(ErrorCode.FORBIDDEN,()->service.review(REVIEWER,OTHER_VERSION));
        assertCode(ErrorCode.FORBIDDEN,()->service.createIssue(OPERATOR_USER,new CreateIssueRequest(OTHER_VERSION,"issue")));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.review(REVIEWER,"bad-id"));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.issues(REVIEWER,VERSION,0,20));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.issues(REVIEWER,VERSION,1,101));
        verify(mapper,never()).lockTask(anyString());
        verifyNoInteractions(identities,authentication,memberships,audit);
    }

    private ReviewService transactional() {
        var permissions=new ResourcePermissionService(identities,authentication,memberships);
        var proxy=new ProxyFactory(new ReviewService(mapper,permissions,audit));
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (ReviewService)proxy.getProxy();
    }
    private static CurrentUser actor(String id,String org,Role role) {
        return new CurrentUser(id,org,"test",List.of(role),0,0);
    }
    private static TaskRow task(String status) { return new TaskRow(TASK,OWNER,OPERATOR,status,3); }
    private static VersionRow version(String status) { return new VersionRow(VERSION,TASK,SUBTASK,REQUIREMENT,EXECUTION,2,status,7); }
    private static ReviewRequest approved() { return new ReviewRequest("APPROVED",null,"PENDING"); }
    private static void assertCode(ErrorCode expected,org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected,assertThrows(BusinessException.class,action).errorCode());
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        private int commits,rollbacks;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
