package com.uav.inspection.inspection;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.inspection.TaskModels.ExecutionView;
import com.uav.inspection.inspection.TaskModels.RequirementUpdate;
import com.uav.inspection.inspection.TaskModels.RequirementView;
import com.uav.inspection.inspection.TaskModels.StartTaskRequest;
import com.uav.inspection.inspection.TaskModels.TaskSummary;
import com.uav.inspection.inspection.TaskModels.UpdateRequirementsRequest;
import com.uav.inspection.inspection.persistence.TaskMapper;
import com.uav.inspection.inspection.persistence.TaskMapper.DeviceRow;
import com.uav.inspection.inspection.persistence.TaskMapper.SubtaskRow;
import org.junit.jupiter.api.Test;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Exercises the real transaction advice; database persistence is covered by TaskFlowProbe. */
class TaskServiceTest {
    private static final String OWNER = ResourcePermissionService.OWNER;
    private static final String OPERATOR = ResourcePermissionService.OPERATOR;
    private static final String TASK = "30000000-0000-4000-8000-000000000001";
    private static final String SUBTASK = "40000000-0000-4000-8000-000000000001";
    private static final String EXECUTION = "50000000-0000-4000-8000-000000000001";
    private static final String REQUIREMENT = "60000000-0000-4000-8000-000000000001";
    private static final String EVIDENCE = "70000000-0000-4000-8000-000000000001";
    private static final String DRONE = "20000000-0000-4000-8000-000000000001";
    private static final Instant NOW = Instant.parse("2026-10-21T02:00:00.123456Z");
    private static final CurrentUser OWNER_ADMIN = user(OWNER, Role.OWNER_TASK_ADMIN);
    private static final CurrentUser OPERATOR_ADMIN = user(OPERATOR, Role.OPERATOR_TASK_ADMIN);

    private final TaskMapper tasks = mock(TaskMapper.class);
    private final ResourcePermissionService permissions = mock(ResourcePermissionService.class);
    private final AuditMapper audit = mock(AuditMapper.class);
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
    private final TaskService service = transactionalService();

    @Test
    void failedParentCompareAndSetRollsBackAfterChildMutationAndDoesNotAudit() {
        when(tasks.lockTask(TASK)).thenReturn(task("DRAFT", 3));
        when(permissions.currentActor(OWNER_ADMIN, true)).thenReturn(OWNER_ADMIN);
        when(tasks.lockRequirements(TASK)).thenReturn(List.of());
        when(tasks.insertRequirement(anyString(), anyString(), eq(TASK), eq("Inspection image"), eq(true)))
                .thenReturn(1);
        when(tasks.touchTask(TASK, 3)).thenReturn(0);

        BusinessException failure = assertThrows(BusinessException.class, () -> service.updateRequirements(
                OWNER_ADMIN, TASK, new UpdateRequirementsRequest(
                        List.of(new RequirementUpdate(null, "Inspection image", true)), 3L)));

        assertEquals(ErrorCode.VERSION_CONFLICT, failure.errorCode());
        var order = inOrder(tasks);
        order.verify(tasks).insertRequirement(anyString(), anyString(), eq(TASK), eq("Inspection image"), eq(true));
        order.verify(tasks).touchTask(TASK, 3);
        verifyNoInteractions(audit);
        verify(tasks, never()).findTask(anyString());
        assertRolledBack();
    }

    @Test
    void auditInsertFailureRollsBackTheSuccessfulBusinessMutation() {
        when(tasks.lockTask(TASK)).thenReturn(task("DRAFT", 3));
        when(permissions.currentActor(OWNER_ADMIN, true)).thenReturn(OWNER_ADMIN);
        when(tasks.lockRequirements(TASK)).thenReturn(List.of());
        when(tasks.insertRequirement(anyString(), anyString(), eq(TASK), eq("Inspection image"), eq(true)))
                .thenReturn(1);
        when(tasks.touchTask(TASK, 3)).thenReturn(1);
        var auditFailure = new DataIntegrityViolationException("Simulated audit insert failure");
        when(audit.insertResourceSuccess(anyString(), eq(OWNER_ADMIN.id()), eq(OWNER),
                eq("TASK_REQUIREMENTS_UPDATED"), eq("INSPECTION_TASK"), eq(TASK), anyString()))
                .thenThrow(auditFailure);

        var failure = assertThrows(DataIntegrityViolationException.class, () -> service.updateRequirements(
                OWNER_ADMIN, TASK, new UpdateRequirementsRequest(
                        List.of(new RequirementUpdate(null, "Inspection image", true)), 3L)));

        assertSame(auditFailure, failure);
        var order = inOrder(tasks, audit);
        order.verify(tasks).insertRequirement(anyString(), anyString(), eq(TASK), eq("Inspection image"), eq(true));
        order.verify(tasks).touchTask(TASK, 3);
        order.verify(audit).insertResourceSuccess(anyString(), eq(OWNER_ADMIN.id()), eq(OWNER),
                eq("TASK_REQUIREMENTS_UPDATED"), eq("INSPECTION_TASK"), eq(TASK), anyString());
        verify(tasks, never()).findTask(anyString());
        assertRolledBack();
    }

    @Test
    void startReusesTheCurrentReadyExecutionAndCommitsWithoutCreatingAnotherBatch() {
        Instant startedAt = NOW.truncatedTo(ChronoUnit.MILLIS);
        var requirement = new RequirementView(REQUIREMENT, EVIDENCE, TASK, SUBTASK,
                "Inspection image", true, 1, 0);
        var subtask = new SubtaskRow(SUBTASK, TASK, "North section", EXECUTION, 4);
        var ready = new ExecutionView(EXECUTION, SUBTASK, DRONE, "READY", 0, null, null, 5);
        var running = new ExecutionView(EXECUTION, SUBTASK, DRONE, "RUNNING", 0, startedAt, null, 6);
        when(tasks.lockTask(TASK)).thenReturn(task("ASSIGNED", 3));
        when(permissions.currentActor(OPERATOR_ADMIN, true)).thenReturn(OPERATOR_ADMIN);
        when(tasks.lockSubtasks(TASK)).thenReturn(List.of(subtask));
        when(tasks.lockRequirements(TASK)).thenReturn(List.of(requirement));
        when(tasks.lockExecutions(SUBTASK)).thenReturn(List.of(ready));
        when(tasks.lockDevice(DRONE)).thenReturn(new DeviceRow(DRONE, OPERATOR, true));
        when(tasks.startExecution(EXECUTION, 5, startedAt)).thenReturn(1);
        when(tasks.touchSubtask(SUBTASK, 4)).thenReturn(1);
        when(tasks.startTask(TASK, 3)).thenReturn(1);
        when(audit.insertResourceSuccess(anyString(), eq(OPERATOR_ADMIN.id()), eq(OPERATOR),
                eq("TASK_STARTED"), eq("INSPECTION_TASK"), eq(TASK), anyString())).thenReturn(1);
        when(tasks.findTask(TASK)).thenReturn(task("RUNNING", 4));
        when(tasks.listMembers(TASK)).thenReturn(List.of());
        when(tasks.listRequirements(TASK)).thenReturn(List.of(requirement));
        when(tasks.listSubtasks(TASK)).thenReturn(List.of(
                new SubtaskRow(SUBTASK, TASK, subtask.name(), EXECUTION, 5)));
        when(tasks.findExecution(EXECUTION)).thenReturn(running);

        var result = service.start(OPERATOR_ADMIN, TASK, new StartTaskRequest(3L));

        assertEquals("RUNNING", result.status());
        assertEquals(EXECUTION, result.subtasks().getFirst().currentExecutionId());
        assertEquals(running, result.subtasks().getFirst().execution());
        verify(tasks).startExecution(EXECUTION, 5, startedAt);
        verify(tasks, never()).insertExecution(anyString(), anyString(), anyString());
        verify(tasks, never()).setInitialExecution(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        assertEquals(1, transactions.begins);
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
    }

    @Test
    void permissionsRevokedBeforeTheLockedRecheckPreventAnyMutationOrAudit() {
        var demoted = user(OWNER, Role.OWNER_REVIEWER);
        when(tasks.lockTask(TASK)).thenReturn(task("DRAFT", 3));
        when(permissions.currentActor(OWNER_ADMIN, true)).thenReturn(demoted);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(permissions)
                .requireTask(demoted, TASK, OWNER, OPERATOR, Role.OWNER_TASK_ADMIN);

        BusinessException failure = assertThrows(BusinessException.class, () -> service.updateRequirements(
                OWNER_ADMIN, TASK, new UpdateRequirementsRequest(List.of(), 3L)));

        assertEquals(ErrorCode.FORBIDDEN, failure.errorCode());
        var order = inOrder(tasks, permissions);
        order.verify(tasks).lockTask(TASK);
        order.verify(permissions).currentActor(OWNER_ADMIN, true);
        order.verify(permissions).requireTask(demoted, TASK, OWNER, OPERATOR, Role.OWNER_TASK_ADMIN);
        verifyNoMoreInteractions(tasks);
        verifyNoInteractions(audit);
        assertRolledBack();
    }

    private TaskService transactionalService() {
        var target = new TaskService(tasks, permissions, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (TaskService) proxy.getProxy();
    }

    private void assertRolledBack() {
        assertEquals(1, transactions.begins);
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    private static TaskSummary task(String status, long version) {
        return new TaskSummary(TASK, OWNER, OPERATOR, "Power line inspection", "North section", status,
                OWNER_ADMIN.id(), version, NOW, NOW);
    }

    private static CurrentUser user(String orgId, Role role) {
        String id = OWNER.equals(orgId) ? "10000000-0000-4000-8000-000000000001"
                : "10000000-0000-4000-8000-000000000004";
        return new CurrentUser(id, orgId, "task-test-user", List.of(role), 0, 0);
    }

    /** Observes Spring's actual annotation-driven commit/rollback decisions without opening a database. */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int begins;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
