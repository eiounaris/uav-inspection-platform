package com.uav.inspection.inspection;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import com.uav.inspection.inspection.TaskModels.*;
import com.uav.inspection.inspection.persistence.TaskMapper;
import com.uav.inspection.inspection.persistence.TaskMapper.CandidateRow;
import com.uav.inspection.inspection.persistence.TaskMapper.DeviceRow;
import com.uav.inspection.inspection.persistence.TaskMapper.SubtaskRow;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("database")
public class TaskService {
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TaskMapper tasks;
    private final ResourcePermissionService permissions;
    private final AuditMapper audit;
    private final Clock clock;

    public TaskService(TaskMapper tasks, ResourcePermissionService permissions, AuditMapper audit, Clock clock) {
        this.tasks = tasks;
        this.permissions = permissions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TaskPage list(CurrentUser supplied, int page, int pageSize) {
        invalidIf(page < 1 || pageSize < 1 || pageSize > 100);
        CurrentUser actor = permissions.currentActor(supplied, false);
        if (!permissions.canReadTasks(actor)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        boolean ownerRead = OWNER.equals(actor.orgId()) && hasOwnerBusinessRole(actor.roles());
        boolean operatorRead = OPERATOR.equals(actor.orgId()) && hasOperatorBusinessRole(actor.roles());
        long total = tasks.countVisible(actor.id(), actor.orgId(), ownerRead, operatorRead);
        List<TaskSummary> items = tasks.listVisible(actor.id(), actor.orgId(), ownerRead, operatorRead,
                pageSize, ((long) page - 1) * pageSize);
        return new TaskPage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public TaskDetail get(CurrentUser supplied, String taskId) {
        validateId(taskId);
        CurrentUser actor = permissions.currentActor(supplied, false);
        TaskSummary task = requireTaskRow(tasks.findTask(taskId));
        permissions.requireTask(actor, task.id(), task.ownerOrgId(), task.operatorOrgId(), null);
        return detail(task);
    }

    @Transactional
    public TaskDetail create(CurrentUser supplied, CreateTaskRequest request) {
        validateName(request.name());
        invalidIf(request.scope() == null || request.scope().isBlank() || request.scope().length() > 16000);
        invalidIf(request.requirements() == null || request.requirements().size() > 100);
        for (RequirementInput requirement : request.requirements()) {
            invalidIf(requirement == null);
            validateName(requirement.name());
            invalidIf(requirement.required() == null);
        }
        // A new task has no existing row to lock. Its insert holds the parent row lock before child inserts.
        CurrentUser actor = permissions.currentActor(supplied, true);
        permissions.requireCreate(actor);
        String taskId = uuid();
        changed(tasks.insertTask(taskId, OWNER, OPERATOR, request.name(), request.scope(), actor.id()));
        changed(tasks.addMember(taskId, actor.id()));
        for (RequirementInput requirement : request.requirements()) {
            changed(tasks.insertRequirement(uuid(), uuid(), taskId, requirement.name(), requirement.required()));
        }
        appendAudit(actor, "TASK_CREATED", "INSPECTION_TASK", taskId);
        return detail(requireTaskRow(tasks.findTask(taskId)));
    }

    @Transactional
    public TaskDetail updateRequirements(CurrentUser supplied, String taskId, UpdateRequirementsRequest request) {
        validateId(taskId);
        TaskSummary task = requireTaskRow(tasks.lockTask(taskId));
        CurrentUser actor = taskActor(supplied, task, Role.OWNER_TASK_ADMIN);
        requireVersion(task.lockVersion(), request.expectedLockVersion());
        conflictIf(!Set.of("DRAFT", "ASSIGNED").contains(task.status()));
        invalidIf(request.requirements() == null || request.requirements().size() > 100);
        Map<String, RequirementView> existing = requirementMap(tasks.lockRequirements(task.id()));
        Set<String> retained = new HashSet<>();
        for (RequirementUpdate update : request.requirements()) {
            invalidIf(update == null);
            validateName(update.name());
            invalidIf(update.required() == null);
            if (update.id() != null) {
                invalidIf(update.id().isBlank() || !existing.containsKey(update.id()) || !retained.add(update.id()));
            }
        }
        // Validate the entire replacement before issuing mutations.
        for (RequirementView old : existing.values()) {
            conflictIf(!retained.contains(old.id()) && old.subtaskId() != null);
        }
        for (RequirementView old : existing.values()) {
            if (!retained.contains(old.id())) {
                changed(tasks.deleteUnboundRequirement(old.id(), task.id()));
            }
        }
        for (RequirementUpdate update : request.requirements()) {
            if (update.id() == null) {
                changed(tasks.insertRequirement(uuid(), uuid(), task.id(), update.name(), update.required()));
            } else {
                RequirementView old = existing.get(update.id());
                changed(tasks.updateRequirement(old.id(), task.id(), update.name(), update.required(), old.lockVersion()));
            }
        }
        changed(tasks.touchTask(task.id(), task.lockVersion()));
        appendAudit(actor, "TASK_REQUIREMENTS_UPDATED", "INSPECTION_TASK", task.id());
        return detail(requireTaskRow(tasks.findTask(task.id())));
    }

    @Transactional
    public TaskDetail assign(CurrentUser supplied, String taskId, AssignTaskRequest request) {
        validateId(taskId);
        TaskSummary task = requireTaskRow(tasks.lockTask(taskId));
        CurrentUser actor = taskActor(supplied, task, Role.OWNER_TASK_ADMIN);
        requireVersion(task.lockVersion(), request.expectedLockVersion());
        conflictIf(!"DRAFT".equals(task.status()));
        invalidIf(!OPERATOR.equals(request.operatorOrgId()));
        validateId(request.operatorAdminId());
        CandidateRow candidate = tasks.findCandidate(request.operatorAdminId());
        invalidIf(candidate == null || !candidate.enabled() || !OPERATOR.equals(candidate.orgId()));
        invalidIf(!parseRoles(candidate.rolesJson()).contains(Role.OPERATOR_TASK_ADMIN));
        changed(tasks.assignTask(task.id(), task.lockVersion()));
        addMember(task.id(), candidate.id());
        appendAudit(actor, "TASK_ASSIGNED", "INSPECTION_TASK", task.id());
        return detail(requireTaskRow(tasks.findTask(task.id())));
    }

    @Transactional
    public TaskDetail replaceMembers(CurrentUser supplied, String taskId, ReplaceMembersRequest request) {
        validateId(taskId);
        TaskSummary task = requireTaskRow(tasks.lockTask(taskId));
        CurrentUser actor = permissions.currentActor(supplied, true);
        Role managerRole = OWNER.equals(actor.orgId()) ? Role.OWNER_TASK_ADMIN : Role.OPERATOR_TASK_ADMIN;
        permissions.requireTask(actor, task.id(), task.ownerOrgId(), task.operatorOrgId(), managerRole);
        requireVersion(task.lockVersion(), request.expectedLockVersion());
        validateIds(request.userIds(), true);
        for (String userId : request.userIds()) {
            CandidateRow candidate = tasks.findCandidate(userId);
            invalidIf(candidate == null || !candidate.enabled() || !actor.orgId().equals(candidate.orgId()));
            List<Role> roles = parseRoles(candidate.rolesJson());
            invalidIf(OWNER.equals(actor.orgId()) ? !hasOwnerBusinessRole(roles) : !hasOperatorBusinessRole(roles));
        }
        tasks.deleteManagedMembers(task.id(), actor.orgId(), task.createdBy());
        for (String userId : request.userIds()) {
            addMember(task.id(), userId);
        }
        changed(tasks.touchTask(task.id(), task.lockVersion()));
        appendAudit(actor, "TASK_MEMBERS_UPDATED", "INSPECTION_TASK", task.id());
        return detail(requireTaskRow(tasks.findTask(task.id())));
    }

    @Transactional
    public TaskDetail createSubtask(CurrentUser supplied, String taskId, CreateSubtaskRequest request) {
        validateId(taskId);
        TaskSummary task = requireTaskRow(tasks.lockTask(taskId));
        CurrentUser actor = taskActor(supplied, task, Role.OPERATOR_TASK_ADMIN);
        requireVersion(task.lockVersion(), request.expectedLockVersion());
        conflictIf(!"ASSIGNED".equals(task.status()));
        validateName(request.name());
        validateIds(request.requirementIds(), false);
        Map<String, RequirementView> requirements = requirementMap(tasks.lockRequirements(task.id()));
        for (String id : request.requirementIds()) {
            RequirementView requirement = requirements.get(id);
            invalidIf(requirement == null);
            conflictIf(requirement.subtaskId() != null);
        }
        String subtaskId = uuid();
        changed(tasks.insertSubtask(subtaskId, task.id(), request.name()));
        for (String id : request.requirementIds().stream().sorted().toList()) {
            changed(tasks.bindRequirement(id, task.id(), subtaskId, requirements.get(id).lockVersion()));
        }
        changed(tasks.touchTask(task.id(), task.lockVersion()));
        appendAudit(actor, "SUBTASK_CREATED", "INSPECTION_TASK", task.id());
        return detail(requireTaskRow(tasks.findTask(task.id())));
    }

    @Transactional
    public ExecutionView createExecution(CurrentUser supplied, String subtaskId, CreateExecutionRequest request) {
        validateId(subtaskId);
        validateId(request.droneId());
        // This first read only locates the immutable parent. The first write lock is always the task row.
        SubtaskRow located = tasks.findSubtask(subtaskId);
        if (located == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        TaskSummary task = requireTaskRow(tasks.lockTask(located.taskId()));
        CurrentUser actor = taskActor(supplied, task, Role.OPERATOR_TASK_ADMIN);
        SubtaskRow subtask = tasks.lockSubtask(subtaskId);
        if (subtask == null || !task.id().equals(subtask.taskId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        requireVersion(subtask.lockVersion(), request.expectedLockVersion());
        conflictIf(!"ASSIGNED".equals(task.status()) || subtask.currentExecutionId() != null);
        conflictIf(tasks.lockExecutions(subtask.id()).stream()
                .anyMatch(execution -> Set.of("READY", "RUNNING").contains(execution.status())));
        DeviceRow device = tasks.lockDevice(request.droneId());
        invalidIf(device == null || !device.enabled() || !task.operatorOrgId().equals(device.operatorOrgId()));
        String executionId = uuid();
        changed(tasks.insertExecution(executionId, subtask.id(), device.id()));
        changed(tasks.setInitialExecution(subtask.id(), executionId, subtask.lockVersion()));
        changed(tasks.touchTask(task.id(), task.lockVersion()));
        appendAudit(actor, "EXECUTION_CREATED", "TASK_EXECUTION", executionId);
        ExecutionView created = tasks.findExecution(executionId);
        if (created == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return created;
    }

    @Transactional
    public TaskDetail start(CurrentUser supplied, String taskId, StartTaskRequest request) {
        validateId(taskId);
        TaskSummary task = requireTaskRow(tasks.lockTask(taskId));
        CurrentUser actor = taskActor(supplied, task, Role.OPERATOR_TASK_ADMIN);
        requireVersion(task.lockVersion(), request.expectedLockVersion());
        conflictIf(!"ASSIGNED".equals(task.status()));
        List<SubtaskRow> subtasks = tasks.lockSubtasks(task.id());
        conflictIf(subtasks.isEmpty());
        List<RequirementView> requirements = tasks.lockRequirements(task.id());
        Set<String> subtaskIds = new HashSet<>(subtasks.stream().map(SubtaskRow::id).toList());
        conflictIf(requirements.stream().anyMatch(requirement -> requirement.subtaskId() == null
                || !subtaskIds.contains(requirement.subtaskId())));
        Map<String, ExecutionView> batches = new LinkedHashMap<>();
        for (SubtaskRow subtask : subtasks) {
            conflictIf(subtask.currentExecutionId() == null || requirements.stream().noneMatch(requirement ->
                    subtask.id().equals(requirement.subtaskId()) && requirement.required()));
            List<ExecutionView> executions = tasks.lockExecutions(subtask.id());
            ExecutionView current = executions.stream().filter(execution ->
                    subtask.currentExecutionId().equals(execution.id())).findFirst().orElse(null);
            conflictIf(current == null || !"READY".equals(current.status())
                    || !subtask.id().equals(current.subtaskId()));
            batches.put(subtask.id(), current);
        }
        // Different tasks can share devices. Acquire their locks in one global, deduplicated order.
        for (String droneId : batches.values().stream().map(ExecutionView::droneId).distinct().sorted().toList()) {
            DeviceRow device = tasks.lockDevice(droneId);
            conflictIf(device == null || !device.enabled() || !task.operatorOrgId().equals(device.operatorOrgId()));
        }
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        for (SubtaskRow subtask : subtasks) {
            ExecutionView execution = batches.get(subtask.id());
            changed(tasks.startExecution(execution.id(), execution.lockVersion(), startedAt));
            changed(tasks.touchSubtask(subtask.id(), subtask.lockVersion()));
        }
        changed(tasks.startTask(task.id(), task.lockVersion()));
        appendAudit(actor, "TASK_STARTED", "INSPECTION_TASK", task.id());
        return detail(requireTaskRow(tasks.findTask(task.id())));
    }

    private CurrentUser taskActor(CurrentUser supplied, TaskSummary task, Role role) {
        CurrentUser current = permissions.currentActor(supplied, true);
        permissions.requireTask(current, task.id(), task.ownerOrgId(), task.operatorOrgId(), role);
        return current;
    }

    private TaskDetail detail(TaskSummary task) {
        List<MemberView> members = tasks.listMembers(task.id()).stream().map(member ->
                new MemberView(member.userId(), member.orgId(), member.username(), parseRoles(member.rolesJson()))).toList();
        List<SubtaskView> subtasks = new ArrayList<>();
        for (SubtaskRow subtask : tasks.listSubtasks(task.id())) {
            ExecutionView execution = subtask.currentExecutionId() == null
                    ? null : tasks.findExecution(subtask.currentExecutionId());
            if (subtask.currentExecutionId() != null && execution == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            subtasks.add(new SubtaskView(subtask.id(), subtask.taskId(), subtask.name(),
                    subtask.currentExecutionId(), subtask.lockVersion(), execution));
        }
        return new TaskDetail(task.id(), task.ownerOrgId(), task.operatorOrgId(), task.name(), task.scope(),
                task.status(), task.createdBy(), task.lockVersion(), task.createdAt(), task.updatedAt(),
                members, tasks.listRequirements(task.id()), List.copyOf(subtasks));
    }

    private static Map<String, RequirementView> requirementMap(List<RequirementView> requirements) {
        Map<String, RequirementView> result = new LinkedHashMap<>();
        for (RequirementView requirement : requirements) {
            result.put(requirement.id(), requirement);
        }
        return result;
    }

    private void addMember(String taskId, String userId) {
        int count = tasks.addMember(taskId, userId);
        if (count != 0 && count != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void appendAudit(CurrentUser actor, String action, String resourceType, String resourceId) {
        String traceId = TraceContext.currentTraceId();
        if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,64}")) {
            traceId = uuid();
        }
        if (audit.insertResourceSuccess(uuid(), actor.id(), actor.orgId(), action,
                resourceType, resourceId, traceId) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static TaskSummary requireTaskRow(TaskSummary row) {
        if (row == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return row;
    }

    private static List<Role> parseRoles(String rolesJson) {
        try {
            return List.copyOf(Arrays.asList(JSON.readValue(rolesJson, Role[].class)));
        } catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static boolean hasOwnerBusinessRole(List<Role> roles) {
        return roles.stream().anyMatch(role -> role == Role.OWNER_TASK_ADMIN || role == Role.OWNER_REVIEWER
                || role == Role.OWNER_DATA_APPROVER);
    }

    private static boolean hasOperatorBusinessRole(List<Role> roles) {
        return roles.stream().anyMatch(role -> role == Role.OPERATOR_TASK_ADMIN || role == Role.OPERATOR_USER);
    }

    private static void validateName(String name) {
        invalidIf(name == null || name.isBlank() || name.length() > 200);
    }

    private static void validateIds(List<String> ids, boolean allowEmpty) {
        invalidIf(ids == null || (!allowEmpty && ids.isEmpty()) || ids.size() > 100);
        invalidIf(ids.stream().anyMatch(id -> id == null || id.isBlank()) || new HashSet<>(ids).size() != ids.size());
        ids.forEach(TaskService::validateId);
    }

    private static void validateId(String id) {
        invalidIf(id == null || id.length() != 36);
        try {
            invalidIf(!UUID.fromString(id).toString().equals(id));
        } catch (IllegalArgumentException failure) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void requireVersion(long actual, Long expected) {
        invalidIf(expected == null || expected < 0);
        conflictIf(actual != expected);
    }

    private static void changed(int count) {
        conflictIf(count != 1);
    }

    private static void invalidIf(boolean condition) {
        if (condition) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void conflictIf(boolean condition) {
        if (condition) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
