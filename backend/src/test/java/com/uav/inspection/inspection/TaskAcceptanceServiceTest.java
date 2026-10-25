package com.uav.inspection.inspection;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.IdentityService;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.identity.persistence.IdentityMapper;
import com.uav.inspection.identity.persistence.TaskScopeMapper;
import com.uav.inspection.inspection.TaskModels.*;
import com.uav.inspection.inspection.persistence.TaskMapper;
import com.uav.inspection.inspection.persistence.TaskMapper.RequirementProgressRow;
import com.uav.inspection.inspection.persistence.TaskMapper.SubtaskRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskAcceptanceServiceTest {
    private static final String TASK="30000000-0000-4000-8000-000000000001";
    private static final String SUBTASK="40000000-0000-4000-8000-000000000001";
    private static final String EXECUTION="50000000-0000-4000-8000-000000000001";
    private static final String REQUIREMENT="60000000-0000-4000-8000-000000000001";
    private static final String OPTIONAL="60000000-0000-4000-8000-000000000002";
    private static final String EVIDENCE="70000000-0000-4000-8000-000000000001";
    private static final String VERSION="80000000-0000-4000-8000-000000000001";
    private static final String DRONE="20000000-0000-4000-8000-000000000001";
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final Instant DATABASE_NOW=Instant.parse("2026-10-24T15:22:33.456Z");
    private static final CurrentUser OWNER_ADMIN=actor("10000000-0000-4000-8000-000000000001",OWNER,Role.OWNER_TASK_ADMIN);
    private static final CurrentUser REVIEWER=actor("10000000-0000-4000-8000-000000000002",OWNER,Role.OWNER_REVIEWER);
    private static final CurrentUser OPERATOR_ADMIN=actor("10000000-0000-4000-8000-000000000004",OPERATOR,Role.OPERATOR_TASK_ADMIN);
    private static final CurrentUser OPERATOR_USER=actor("10000000-0000-4000-8000-000000000006",OPERATOR,Role.OPERATOR_USER);
    private final TaskMapper tasks=mock(TaskMapper.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final IdentityMapper identities=mock(IdentityMapper.class);
    private final IdentityService authentication=mock(IdentityService.class);
    private final TaskScopeMapper memberships=mock(TaskScopeMapper.class);
    private final Map<String,CurrentUser> currentUsers=new HashMap<>();
    private final AtomicReference<String> status=new AtomicReference<>("RUNNING");
    private final RecordingManager manager=new RecordingManager();
    private final TaskService service=transactional();

    @BeforeEach
    void configure() {
        for(CurrentUser actor:List.of(OWNER_ADMIN,REVIEWER,OPERATOR_ADMIN,OPERATOR_USER)) currentUsers.put(actor.id(),actor);
        when(authentication.authenticate(anyString(),anyLong())).thenAnswer(call->currentUsers.get(call.getArgument(0)));
        when(memberships.countMembership(eq(TASK),anyString())).thenReturn(1);
        when(tasks.lockTask(TASK)).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return task(status.get(),5);
        });
        when(tasks.findTask(TASK)).thenAnswer(call->task(status.get(),6));
        when(tasks.listMembers(TASK)).thenReturn(List.of());
        when(tasks.listSubtasks(TASK)).thenReturn(List.of(subtask()));
        when(tasks.listRequirements(TASK)).thenReturn(List.of(requirement(REQUIREMENT,SUBTASK,true),requirement(OPTIONAL,SUBTASK,false)));
        when(tasks.findExecution(EXECUTION)).thenReturn(execution(EXECUTION,SUBTASK,"RUNNING"));
        when(tasks.requirementProgress(TASK)).thenReturn(List.of(latest("APPROVED","CONFIRMED")));
        when(tasks.submitAcceptance(TASK,5)).thenAnswer(call->{status.set("WAITING_ACCEPTANCE"); return 1;});
        when(tasks.completeTask(TASK,5)).thenAnswer(call->{status.set("COMPLETED"); return 1;});
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
    }

    @Test
    void submitAcceptsFixedCurrentMaterialsPendingReviewAndAnchorWithoutWaitingForOtherUploads() {
        when(tasks.requirementProgress(TASK)).thenReturn(List.of(latest("PENDING","PENDING")));
        when(tasks.countCurrentVerifyingUploads(TASK)).thenReturn(3L);

        TaskDetail result=service.submitAcceptance(OPERATOR_ADMIN,TASK,new SubmitAcceptanceRequest(5L));

        assertEquals("WAITING_ACCEPTANCE",result.status()); assertEquals(6,result.lockVersion()); assertNull(result.completedAt());
        var order=inOrder(tasks,identities,authentication,memberships,audit);
        order.verify(tasks).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(OPERATOR_ADMIN.id());
        order.verify(authentication).authenticate(OPERATOR_ADMIN.id(),0);
        order.verify(memberships).countMembership(TASK,OPERATOR_ADMIN.id());
        order.verify(tasks).requirementProgress(TASK);
        order.verify(tasks).submitAcceptance(TASK,5);
        order.verify(audit).insertResourceSuccess(anyString(),eq(OPERATOR_ADMIN.id()),eq(OPERATOR),eq("TASK_ACCEPTANCE_SUBMITTED"),eq("INSPECTION_TASK"),eq(TASK),anyString());
        verify(tasks,never()).countCurrentVerifyingUploads(anyString());
        verify(tasks,never()).findDevice(anyString()); verify(tasks,never()).lockDevice(anyString());
        assertEquals(1,manager.commits); assertEquals(0,manager.rollbacks);
    }

    @Test
    void completeChecksTheLatestCurrentRequiredVersionAndReturnsTheDatabaseCompletionTime() {
        status.set("WAITING_ACCEPTANCE");

        TaskDetail result=service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L));

        assertEquals("COMPLETED",result.status()); assertEquals(6,result.lockVersion());
        assertEquals(DATABASE_NOW,result.completedAt());
        // The execution progress and device enablement are not acceptance criteria.
        assertEquals(35,result.subtasks().getFirst().execution().progress());
        verify(tasks,never()).findDevice(anyString()); verify(tasks,never()).lockDevice(anyString());
        var order=inOrder(tasks,identities,authentication,memberships,audit);
        order.verify(tasks).lockTask(TASK);
        order.verify(identities).findUserByIdForUpdate(OWNER_ADMIN.id());
        order.verify(authentication).authenticate(OWNER_ADMIN.id(),0);
        order.verify(memberships).countMembership(TASK,OWNER_ADMIN.id());
        order.verify(tasks).requirementProgress(TASK);
        order.verify(tasks).countCurrentVerifyingUploads(TASK);
        order.verify(tasks).completeTask(TASK,5);
        order.verify(audit).insertResourceSuccess(anyString(),eq(OWNER_ADMIN.id()),eq(OWNER),eq("TASK_COMPLETED"),eq("INSPECTION_TASK"),eq(TASK),anyString());
        assertEquals(1,manager.commits); assertEquals(0,manager.rollbacks);
    }

    @Test
    void onlyTheCorrectOrganizationAdministratorWithCurrentMembershipCanChangeAcceptanceState() {
        assertCode(ErrorCode.FORBIDDEN,()->service.submitAcceptance(OWNER_ADMIN,TASK,new SubmitAcceptanceRequest(5L)));
        assertCode(ErrorCode.FORBIDDEN,()->service.submitAcceptance(OPERATOR_USER,TASK,new SubmitAcceptanceRequest(5L)));
        status.set("WAITING_ACCEPTANCE");
        assertCode(ErrorCode.FORBIDDEN,()->service.complete(OPERATOR_ADMIN,TASK,new CompleteTaskRequest(5L)));
        assertCode(ErrorCode.FORBIDDEN,()->service.complete(REVIEWER,TASK,new CompleteTaskRequest(5L)));
        when(memberships.countMembership(TASK,OWNER_ADMIN.id())).thenReturn(0);
        assertCode(ErrorCode.FORBIDDEN,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L)));
        currentUsers.put(OPERATOR_ADMIN.id(),actor(OPERATOR_ADMIN.id(),OPERATOR,Role.OPERATOR_USER));
        status.set("RUNNING");
        assertCode(ErrorCode.FORBIDDEN,()->service.submitAcceptance(OPERATOR_ADMIN,TASK,new SubmitAcceptanceRequest(5L)));
        verify(tasks,never()).listSubtasks(anyString());
        verify(tasks,never()).completeTask(anyString(),anyLong());
        verify(tasks,never()).submitAcceptance(anyString(),anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void staleOrMissingExpectedVersionsStopBeforeAcceptanceQueries() {
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.submitAcceptance(OPERATOR_ADMIN,TASK,new SubmitAcceptanceRequest(4L)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.submitAcceptance(OPERATOR_ADMIN,TASK,new SubmitAcceptanceRequest(null)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.submitAcceptance(OPERATOR_ADMIN,TASK,new SubmitAcceptanceRequest(-1L)));
        status.set("WAITING_ACCEPTANCE");
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(4L)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(null)));
        verify(tasks,never()).listSubtasks(anyString());
        verify(tasks,never()).completeTask(anyString(),anyLong());
        verify(tasks,never()).submitAcceptance(anyString(),anyLong());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings={"DRAFT","ASSIGNED","WAITING_ACCEPTANCE","COMPLETED"})
    void submitOnlyTransitionsFromRunning(String current) {
        status.set(current);
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.submitAcceptance(OPERATOR_ADMIN,TASK,new SubmitAcceptanceRequest(5L)));
        verify(tasks,never()).listSubtasks(anyString());
        verify(tasks,never()).submitAcceptance(anyString(),anyLong()); verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings={"DRAFT","ASSIGNED","RUNNING","COMPLETED"})
    void completeOnlyTransitionsFromWaitingAcceptanceAndDoesNotTreatARepeatAsNewSuccess(String current) {
        status.set(current);
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L)));
        verify(tasks,never()).listSubtasks(anyString());
        verify(tasks,never()).completeTask(anyString(),anyLong()); verifyNoInteractions(audit);
    }

    @Test
    void anEmptyTaskCannotPassEitherStepVacuously() {
        when(tasks.listSubtasks(TASK)).thenReturn(List.of());
        bothStepsReject();
        verify(tasks,never()).requirementProgress(anyString());
    }

    @Test
    void eachSubtaskNeedsAtLeastOneRequiredMaterialEvenIfOtherSubtasksAreComplete() {
        String second="40000000-0000-4000-8000-000000000002";
        when(tasks.listSubtasks(TASK)).thenReturn(List.of(subtask(),new SubtaskRow(second,TASK,"South","other-execution",0)));
        bothStepsReject();
        verify(tasks,never()).requirementProgress(anyString());
    }

    @Test
    void unboundOptionalMaterialsAlsoPreventAcceptanceBecauseEveryRequirementMustBeAssigned() {
        when(tasks.listRequirements(TASK)).thenReturn(List.of(requirement(REQUIREMENT,SUBTASK,true),requirement(OPTIONAL,null,false)));
        bothStepsReject();
        verify(tasks,never()).requirementProgress(anyString());
    }

    @Test
    void aCurrentPointerMustResolveToTheSameSubtasksRunningExecution() {
        for(ExecutionView execution:List.of(execution(EXECUTION,SUBTASK,"READY"),execution(EXECUTION,"other-subtask","RUNNING"))) {
            when(tasks.findExecution(EXECUTION)).thenReturn(execution);
            bothStepsReject();
        }
        when(tasks.findExecution(EXECUTION)).thenReturn(null);
        bothStepsReject();
        verify(tasks,never()).requirementProgress(anyString());
    }

    @Test
    void missingCurrentFixedVersionsCannotBeReplacedByHistoricalVersions() {
        when(tasks.requirementProgress(TASK)).thenReturn(List.of(new RequirementProgressRow(REQUIREMENT,EVIDENCE,"Photo",true,
                SUBTASK,EXECUTION,null,null,null,null,5)));
        bothStepsReject();
    }

    @Test
    void aVersionFromADifferentExecutionCannotSatisfyCurrentAcceptance() {
        when(tasks.requirementProgress(TASK)).thenReturn(List.of(new RequirementProgressRow(REQUIREMENT,EVIDENCE,"Photo",true,
                SUBTASK,"historical-execution",VERSION,99,"APPROVED","CONFIRMED",5)));
        bothStepsReject();
    }

    @ParameterizedTest
    @ValueSource(strings={"PENDING","RETURNED"})
    void completionRequiresTheLatestCurrentReviewToBeApproved(String review) {
        status.set("WAITING_ACCEPTANCE");
        when(tasks.requirementProgress(TASK)).thenReturn(List.of(latest(review,"CONFIRMED")));
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L)));
        verify(tasks,never()).completeTask(anyString(),anyLong()); verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings={"PENDING","SUBMITTED","UNKNOWN","FAILED"})
    void completionRequiresTheLatestCurrentAnchorToBeConfirmed(String anchor) {
        status.set("WAITING_ACCEPTANCE");
        when(tasks.requirementProgress(TASK)).thenReturn(List.of(latest("APPROVED",anchor)));
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L)));
        verify(tasks,never()).completeTask(anyString(),anyLong()); verifyNoInteractions(audit);
    }

    @Test
    void anyCurrentVerifyingUploadBlocksCompletionIncludingOptionalCorrections() {
        status.set("WAITING_ACCEPTANCE");
        when(tasks.countCurrentVerifyingUploads(TASK)).thenReturn(1L);
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L)));
        verify(tasks,never()).completeTask(anyString(),anyLong()); verifyNoInteractions(audit);
    }

    @Test
    void aFailedStateCompareAndSetDoesNotAppendSuccessAudit() {
        doReturn(0).when(tasks).submitAcceptance(TASK,5);
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.submitAcceptance(OPERATOR_ADMIN,TASK,new SubmitAcceptanceRequest(5L)));
        status.set("WAITING_ACCEPTANCE");
        doReturn(0).when(tasks).completeTask(TASK,5);
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L)));
        verifyNoInteractions(audit); assertEquals(0,manager.commits); assertEquals(2,manager.rollbacks);
    }

    @Test
    void auditFailureRollsBackTheSuccessfulCompletionAndItsTimestamp() {
        status.set("WAITING_ACCEPTANCE");
        var failure=new DataIntegrityViolationException("audit insert failed");
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenThrow(failure);
        assertSame(failure,assertThrows(DataIntegrityViolationException.class,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L))));
        verify(tasks).completeTask(TASK,5); verify(tasks,never()).findTask(anyString());
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void completedTasksRejectFurtherTaskBusinessWritesButAllowMembershipRevocation() {
        status.set("COMPLETED");
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.updateRequirements(OWNER_ADMIN,TASK,new UpdateRequirementsRequest(List.of(),5L)));
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.createSubtask(OPERATOR_ADMIN,TASK,new CreateSubtaskRequest("Extra",List.of(REQUIREMENT),5L)));
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.start(OPERATOR_ADMIN,TASK,new StartTaskRequest(5L)));
        when(tasks.findSubtask(SUBTASK)).thenReturn(subtask()); when(tasks.lockSubtask(SUBTASK)).thenReturn(subtask());
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.createExecution(OPERATOR_ADMIN,SUBTASK,new CreateExecutionRequest(DRONE,2L,"replacement")));
        when(tasks.touchTask(TASK,5)).thenReturn(1);

        TaskDetail result=service.replaceMembers(OWNER_ADMIN,TASK,new ReplaceMembersRequest(List.of(),5L));

        assertEquals("COMPLETED",result.status()); assertEquals(DATABASE_NOW,result.completedAt());
        verify(tasks).deleteManagedMembers(TASK,OWNER,OWNER_ADMIN.id());
        verify(tasks,never()).insertSubtask(anyString(),anyString(),anyString());
        verify(tasks,never()).startTask(anyString(),anyLong());
        verify(tasks,never()).insertExecution(anyString(),anyString(),anyString());
        verify(tasks,never()).closeExecution(anyString(),anyLong(),any());
        verify(tasks,never()).lockRequirements(anyString());
    }

    @Test
    void invalidRequestIdsAndUnknownTasksDoNotExposeAcceptanceData() {
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.submitAcceptance(OPERATOR_ADMIN,"bad-id",new SubmitAcceptanceRequest(5L)));
        assertCode(ErrorCode.INVALID_ARGUMENT,()->service.complete(OWNER_ADMIN,TASK,null));
        doReturn(null).when(tasks).lockTask(TASK);
        assertCode(ErrorCode.FORBIDDEN,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L)));
        verify(tasks,never()).listSubtasks(anyString()); verifyNoInteractions(identities,authentication,memberships,audit);
    }

    private void bothStepsReject() {
        status.set("RUNNING");
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.submitAcceptance(OPERATOR_ADMIN,TASK,new SubmitAcceptanceRequest(5L)));
        status.set("WAITING_ACCEPTANCE");
        assertCode(ErrorCode.VERSION_CONFLICT,()->service.complete(OWNER_ADMIN,TASK,new CompleteTaskRequest(5L)));
        verify(tasks,never()).submitAcceptance(anyString(),anyLong());
        verify(tasks,never()).completeTask(anyString(),anyLong()); verifyNoInteractions(audit);
    }
    private TaskService transactional() {
        var permissions=new ResourcePermissionService(identities,authentication,memberships);
        var proxy=new ProxyFactory(new TaskService(tasks,permissions,audit,Clock.fixed(DATABASE_NOW.plusSeconds(3600),ZoneOffset.UTC)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (TaskService)proxy.getProxy();
    }
    private static CurrentUser actor(String id,String org,Role role) { return new CurrentUser(id,org,"test",List.of(role),0,0); }
    private static TaskSummary task(String state,long version) {
        return new TaskSummary(TASK,OWNER,OPERATOR,"Inspection","North",state,OWNER_ADMIN.id(),version,
                DATABASE_NOW.minusSeconds(3600),DATABASE_NOW,"COMPLETED".equals(state)?DATABASE_NOW:null);
    }
    private static SubtaskRow subtask() { return new SubtaskRow(SUBTASK,TASK,"North",EXECUTION,2); }
    private static RequirementView requirement(String id,String subtask,boolean required) {
        return new RequirementView(id,EVIDENCE,TASK,subtask,"Photo",required,4,0);
    }
    private static ExecutionView execution(String id,String subtask,String state) {
        return new ExecutionView(id,subtask,DRONE,state,35,DATABASE_NOW.minusSeconds(3600),null,0);
    }
    private static RequirementProgressRow latest(String review,String anchor) {
        return new RequirementProgressRow(REQUIREMENT,EVIDENCE,"Photo",true,SUBTASK,EXECUTION,VERSION,3,review,anchor,4);
    }
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
