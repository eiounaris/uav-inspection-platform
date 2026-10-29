package com.uav.inspection.inspection;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.inspection.TaskModels.*;
import com.uav.inspection.inspection.persistence.TaskMapper;
import com.uav.inspection.inspection.persistence.TaskMapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskExecutionServiceTest {
    private static final String TASK="30000000-0000-4000-8000-000000000001";
    private static final String SUBTASK="40000000-0000-4000-8000-000000000001";
    private static final String OLD_EXEC="50000000-0000-4000-8000-000000000001";
    private static final String OLD_DEVICE="20000000-0000-4000-8000-000000000003";
    private static final String NEW_DEVICE="20000000-0000-4000-8000-000000000001";
    private static final String OWNER=ResourcePermissionService.OWNER,OPERATOR=ResourcePermissionService.OPERATOR;
    private static final Instant NOW=Instant.parse("2026-10-24T01:00:00.123Z");
    private static final CurrentUser ADMIN=new CurrentUser("10000000-0000-4000-8000-000000000004",OPERATOR,"operator-admin",List.of(Role.OPERATOR_TASK_ADMIN),0,0);
    private final TaskMapper tasks=mock(TaskMapper.class);
    private final ResourcePermissionService permissions=mock(ResourcePermissionService.class);
    private final AuditMapper audit=mock(AuditMapper.class);
    private final RecordingManager manager=new RecordingManager();
    private final TaskService service=transactional();

    @BeforeEach
    void configure() {
        var subtask=new SubtaskRow(SUBTASK,TASK,"North",OLD_EXEC,4);
        when(tasks.findSubtask(SUBTASK)).thenReturn(subtask);
        when(tasks.lockSubtask(SUBTASK)).thenReturn(subtask);
        when(tasks.lockTask(TASK)).thenReturn(task("WAITING_ACCEPTANCE"));
        when(permissions.currentActor(ADMIN,true)).thenReturn(ADMIN);
        when(tasks.lockExecutions(SUBTASK)).thenReturn(List.of(new ExecutionView(OLD_EXEC,SUBTASK,OLD_DEVICE,"RUNNING",80,NOW.minusSeconds(90),null,7)));
        when(tasks.lockDevice(OLD_DEVICE)).thenReturn(new DeviceRow(OLD_DEVICE,OPERATOR,false));
        when(tasks.lockDevice(NEW_DEVICE)).thenReturn(new DeviceRow(NEW_DEVICE,OPERATOR,true));
        when(tasks.databaseNow()).thenReturn(NOW);
        when(tasks.closeExecution(OLD_EXEC,7,NOW)).thenReturn(1);
        when(tasks.insertRunningExecution(anyString(),eq(SUBTASK),eq(NEW_DEVICE),eq(NOW))).thenReturn(1);
        when(tasks.replaceCurrentExecution(eq(SUBTASK),eq(OLD_EXEC),anyString(),eq(4L))).thenReturn(1);
        when(tasks.insertAdjustment(any())).thenReturn(1);
        when(tasks.touchAdjustedTask(TASK,3)).thenReturn(1);
        when(audit.insertResourceSuccess(anyString(),eq(ADMIN.id()),eq(OPERATOR),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        when(tasks.findExecution(anyString())).thenAnswer(call->new ExecutionView(call.getArgument(0),SUBTASK,NEW_DEVICE,"RUNNING",0,NOW,null,0));
    }

    @Test
    void replacementClosesTheOldBatchAndCreatesANewZeroProgressBatchWithOneAtomicAdjustment() {
        ExecutionView created=service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L,"  Camera failure  "));
        assertEquals("RUNNING",created.status()); assertEquals(0,created.progress());
        assertNotEquals(OLD_EXEC,created.id()); assertEquals(NOW,created.startedAt());
        var order=inOrder(tasks,permissions,audit);
        order.verify(tasks).lockTask(TASK);
        order.verify(permissions).currentActor(ADMIN,true);
        order.verify(permissions).requireTask(ADMIN,TASK,OWNER,OPERATOR,Role.OPERATOR_TASK_ADMIN);
        order.verify(tasks).lockSubtask(SUBTASK);
        order.verify(tasks).lockExecutions(SUBTASK);
        order.verify(tasks).lockDevice(NEW_DEVICE);
        order.verify(tasks).lockDevice(OLD_DEVICE);
        order.verify(tasks).closeExecution(OLD_EXEC,7,NOW);
        order.verify(tasks).insertRunningExecution(created.id(),SUBTASK,NEW_DEVICE,NOW);
        order.verify(tasks).replaceCurrentExecution(SUBTASK,OLD_EXEC,created.id(),4);
        var adjustment=ArgumentCaptor.forClass(AdjustmentRow.class);
        order.verify(tasks).insertAdjustment(adjustment.capture());
        assertEquals(OLD_EXEC,adjustment.getValue().previousExecutionId());
        assertEquals(created.id(),adjustment.getValue().newExecutionId());
        assertEquals(ADMIN.id(),adjustment.getValue().actorId());
        assertEquals("Camera failure",adjustment.getValue().reason());
        assertEquals(NOW,adjustment.getValue().createdAt());
        order.verify(tasks).touchAdjustedTask(TASK,3);
        order.verify(audit).insertResourceSuccess(anyString(),eq(ADMIN.id()),eq(OPERATOR),eq("EXECUTION_ADJUSTED"),eq("TASK_EXECUTION"),eq(created.id()),anyString());
        assertEquals(1,manager.commits); assertEquals(0,manager.rollbacks);
    }

    @Test
    void aDisabledNewDeviceCannotCloseTheOldBatchEvenThoughTheOldDeviceMayBeDisabled() {
        when(tasks.lockDevice(NEW_DEVICE)).thenReturn(new DeviceRow(NEW_DEVICE,OPERATOR,false));
        var failure=assertThrows(BusinessException.class,()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L,"replacement")));
        assertEquals(ErrorCode.INVALID_ARGUMENT,failure.errorCode());
        verify(tasks,never()).closeExecution(anyString(),anyLong(),any());
        verifyNoInteractions(audit);
        assertEquals(1,manager.rollbacks);
    }

    @Test
    void staleSubtaskVersionStopsBeforeAnyExecutionOrDeviceLocks() {
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,3L,"replacement"))).errorCode());
        verify(tasks,never()).lockExecutions(anyString());
        verify(tasks,never()).lockDevice(anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void missingReasonAndSameDeviceAreRejectedWithoutMutation() {
        assertEquals(ErrorCode.INVALID_ARGUMENT,assertThrows(BusinessException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L))).errorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT,assertThrows(BusinessException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L," "))).errorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT,assertThrows(BusinessException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L,"\u00a0\u2007\u202f"))).errorCode());
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(OLD_DEVICE,4L,"same device"))).errorCode());
        verify(tasks,never()).closeExecution(anyString(),anyLong(),any());
        verifyNoInteractions(audit);
    }

    @Test
    void failedPointerCompareAndSetRollsBackTheClosedAndInsertedExecutionsBeforeHistoryOrAudit() {
        when(tasks.replaceCurrentExecution(eq(SUBTASK),eq(OLD_EXEC),anyString(),eq(4L))).thenReturn(0);
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L,"replacement"))).errorCode());
        verify(tasks).closeExecution(OLD_EXEC,7,NOW);
        verify(tasks).insertRunningExecution(anyString(),eq(SUBTASK),eq(NEW_DEVICE),eq(NOW));
        verify(tasks,never()).insertAdjustment(any());
        verifyNoInteractions(audit);
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void historyAndBusinessWritesRollBackTogetherWhenAuditInsertionFails() {
        var failure=new DataIntegrityViolationException("Simulated audit persistence failure");
        when(audit.insertResourceSuccess(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenThrow(failure);
        assertSame(failure,assertThrows(DataIntegrityViolationException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L,"replacement"))));
        verify(tasks).insertAdjustment(any());
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
    }

    @Test
    void completedTasksAndRevokedTaskMembershipCannotBeAdjusted() {
        when(tasks.lockTask(TASK)).thenReturn(task("COMPLETED"));
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L,"replacement"))).errorCode());
        when(tasks.lockTask(TASK)).thenReturn(task("RUNNING"));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(permissions).requireTask(ADMIN,TASK,OWNER,OPERATOR,Role.OPERATOR_TASK_ADMIN);
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,
                ()->service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L,"replacement"))).errorCode());
        verify(tasks,never()).closeExecution(anyString(),anyLong(),any());
        verifyNoInteractions(audit);
    }

    @Test
    void initialAllocationStillSupportsTheOriginalTwoArgumentRequestAndCreatesReadyWithoutAdjustment() {
        when(tasks.lockTask(TASK)).thenReturn(task("ASSIGNED"));
        when(tasks.lockSubtask(SUBTASK)).thenReturn(new SubtaskRow(SUBTASK,TASK,"North",null,4));
        when(tasks.lockExecutions(SUBTASK)).thenReturn(List.of());
        when(tasks.insertExecution(anyString(),eq(SUBTASK),eq(NEW_DEVICE))).thenReturn(1);
        when(tasks.setInitialExecution(eq(SUBTASK),anyString(),eq(4L))).thenReturn(1);
        when(tasks.touchTask(TASK,3)).thenReturn(1);
        when(tasks.findExecution(anyString())).thenAnswer(call->new ExecutionView(call.getArgument(0),SUBTASK,NEW_DEVICE,"READY",0,null,null,0));
        assertEquals("READY",service.createExecution(ADMIN,SUBTASK,new CreateExecutionRequest(NEW_DEVICE,4L)).status());
        verify(tasks,never()).insertRunningExecution(anyString(),anyString(),anyString(),any());
        verify(tasks,never()).insertAdjustment(any());
        verify(tasks,never()).databaseNow();
    }

    @Test
    void progressSeparatesPresentMaterialsFromApprovedAndConfirmedMaterialsAndDoesNotCompleteTheTask() {
        when(tasks.lockTask(TASK)).thenReturn(task("RUNNING"));
        when(tasks.subtaskProgress(TASK)).thenReturn(List.of(
                new SubtaskProgress(SUBTASK,"North","current",NEW_DEVICE,"RUNNING",100,1),
                new SubtaskProgress("second","South",null,null,null,0,0),
                new SubtaskProgress("third","West","third-exec",OLD_DEVICE,"RUNNING",20,0)));
        when(tasks.requirementProgress(TASK)).thenReturn(List.of(
                new RequirementProgressRow("required-present","ev1","Photo",true,SUBTASK,"current","current-version",1,"PENDING","CONFIRMED",2),
                new RequirementProgressRow("required-missing","ev2","Video",true,"second",null,null,null,null,null,1),
                new RequirementProgressRow("optional","ev3","Optional",false,"third","third-exec","optional-version",7,"APPROVED","CONFIRMED",0)));
        TaskProgress progress=service.progress(ADMIN,TASK);
        assertEquals("RUNNING",progress.status()); assertEquals(40.0,progress.averageProgress());
        assertEquals(2,progress.requiredCount()); assertEquals(1,progress.presentRequiredCount());
        assertEquals(0,progress.acceptanceReadyRequiredCount()); assertEquals(1,progress.missingRequiredCount());
        assertEquals(List.of("required-missing"),progress.missingRequiredIds());
        assertEquals(3,progress.historicalVersionCount());
        assertTrue(progress.requirements().getFirst().present()); assertFalse(progress.requirements().getFirst().acceptanceReady());
        assertEquals("current-version",progress.requirements().getFirst().versionId());
        verify(tasks,never()).touchAdjustedTask(anyString(),anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void historyPaginationChecksCurrentMembershipBeforeReadingRows() {
        when(tasks.countExecutions(SUBTASK)).thenReturn(3L);
        when(tasks.pageExecutions(SUBTASK,1,1)).thenReturn(List.of());
        var page=service.executions(ADMIN,SUBTASK,2,1);
        assertEquals(3,page.total()); assertEquals(2,page.page());
        var order=inOrder(tasks,permissions);
        order.verify(tasks).lockTask(TASK);
        order.verify(permissions).currentActor(ADMIN,true);
        order.verify(permissions).requireTask(ADMIN,TASK,OWNER,OPERATOR,null);
        order.verify(tasks).countExecutions(SUBTASK);
        order.verify(tasks).pageExecutions(SUBTASK,1,1);
        verifyNoInteractions(audit);
    }

    private TaskService transactional() {
        var proxy=new ProxyFactory(new TaskService(tasks,permissions,audit,Clock.fixed(NOW.plusSeconds(600),ZoneOffset.UTC)));
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (TaskService)proxy.getProxy();
    }
    private static TaskSummary task(String status) {
        return new TaskSummary(TASK,OWNER,OPERATOR,"Inspection","North",status,"owner",3,NOW.minusSeconds(3600),NOW);
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        private int commits,rollbacks;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
