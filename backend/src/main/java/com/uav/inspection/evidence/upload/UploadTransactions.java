package com.uav.inspection.evidence.upload;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.evidence.storage.UploadSettings;
import com.uav.inspection.evidence.upload.UploadModels.*;
import com.uav.inspection.evidence.upload.persistence.UploadMapper;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.*;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.identity.ResourcePermissionService;
import com.uav.inspection.identity.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Short database phases only. File streams and object-storage calls stay in UploadService. */
@Service
@Profile("database & storage")
public class UploadTransactions {
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private final UploadMapper uploads;
    private final ResourcePermissionService permissions;
    private final AuditMapper audit;
    private final UploadSettings settings;

    public UploadTransactions(UploadMapper uploads, ResourcePermissionService permissions,
                              AuditMapper audit, UploadSettings settings) {
        this.uploads = uploads;
        this.permissions = permissions;
        this.audit = audit;
        this.settings = settings;
    }

    @Transactional
    public UploadProgress create(CurrentUser supplied, CreateUploadRequest request, String requestHash) {
        requireSupplied(supplied);
        SessionRow existing = uploads.findByRequest(supplied.id(), request.clientRequestId());
        if (existing != null) {
            LockedUpload locked = lockUpload(supplied, existing);
            sameRequest(locked.session(), requestHash);
            return progress(locked.session());
        }
        RequirementRow located = uploads.findRequirement(request.requirementId());
        Scope scope = lockScope(supplied, located, request.executionId());
        // The actor row lock serializes this user's keys even when competing requests name different tasks.
        existing = uploads.lockByRequest(scope.actor().id(), request.clientRequestId());
        if (existing != null) {
            sameRequest(existing, requestHash);
            if (!existing.taskId().equals(scope.task().id())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            owner(existing, scope.actor());
            return progress(existing);
        }
        active(scope);
        long count = 1 + (request.size() - 1) / settings.partSize();
        UploadValidation.invalid(count > Integer.MAX_VALUE);
        Instant now = uploads.databaseNow();
        Instant expiry = now.plus(settings.sessionTtl()).truncatedTo(ChronoUnit.MILLIS);
        SessionRow session = new SessionRow(UUID.randomUUID().toString(), scope.task().id(), scope.subtask().id(),
                scope.requirement().id(), scope.requirement().evidenceId(), scope.execution().id(), OPERATOR,
                "USER", scope.actor().id(), request.clientRequestId(), requestHash, request.filename(),
                request.contentType(), request.size(), settings.partSize(), (int) count, "UPLOADING", expiry,
                0, now, now);
        one(uploads.insertSession(session));
        appendAudit(scope.actor(), "UPLOAD_SESSION_CREATED", session.id());
        return progress(session);
    }

    @Transactional(readOnly = true)
    public UploadProgress get(CurrentUser supplied, String uploadId) {
        SessionRow session = requireSession(uploads.findSession(uploadId));
        CurrentUser actor = permissions.currentActor(supplied, false);
        TaskRow task = requireTask(uploads.findTask(session.taskId()));
        authorize(actor, task);
        owner(session, actor);
        return progress(session);
    }

    @Transactional
    public PartPlan preparePart(CurrentUser supplied, String uploadId, int partNo) {
        LockedUpload locked = lockUpload(supplied, requireSession(uploads.findSession(uploadId)));
        writable(locked);
        return plan(locked.session(), partNo);
    }

    /** Called from the completion transaction so permission and scope locks live through its commit. */
    @Transactional
    public SessionRow lockForCompletion(CurrentUser supplied, String uploadId) {
        UploadValidation.id(uploadId);
        LockedUpload locked = lockUpload(supplied, requireSession(uploads.findSession(uploadId)));
        if ("UPLOADING".equals(locked.session().status()) || "EXPIRED".equals(locked.session().status())) {
            writable(locked);
        }
        return locked.session();
    }

    /** Returns a known identical part, or null when an object must be saved. */
    @Transactional
    public PartResult findIdenticalPart(CurrentUser supplied, String uploadId, int partNo, long size, String sha256) {
        LockedUpload locked = lockUpload(supplied, requireSession(uploads.findSession(uploadId)));
        writable(locked);
        UploadValidation.exactLength(size, plan(locked.session(), partNo).expectedSize());
        PartRow existing = uploads.findPart(uploadId, partNo);
        if (existing == null) {
            return null;
        }
        samePart(existing, size, sha256);
        return result(existing);
    }

    @Transactional
    public PartCommit storePart(CurrentUser supplied, String uploadId, int partNo, long size,
                                 String sha256, String candidateKey) {
        LockedUpload locked = lockUpload(supplied, requireSession(uploads.findSession(uploadId)));
        writable(locked);
        SessionRow session = locked.session();
        UploadValidation.exactLength(size, plan(session, partNo).expectedSize());
        UploadValidation.invalid(sha256 == null || !sha256.matches("[0-9a-f]{64}"));
        String prefix = "upload-parts/" + uploadId + "/" + partNo + "/";
        UploadValidation.invalid(candidateKey == null || !candidateKey.startsWith(prefix));
        UploadValidation.id(candidateKey.substring(prefix.length()));
        PartRow existing = uploads.findPart(uploadId, partNo);
        if (existing != null) {
            // A normal transaction return proves the candidate was not referenced. The caller can then
            // remove its losing object, including a different-content race, before reporting a conflict.
            return new PartCommit(result(existing), existing.objectKey());
        }
        PartRow part = new PartRow(uploadId, partNo, size, sha256, candidateKey);
        one(uploads.insertPart(part));
        if (uploads.touchWritableSession(uploadId, session.lockVersion()) != 1) {
            // Expiry can advance between the eligibility read and conditional write.
            if (!session.expiresAt().isAfter(uploads.databaseNow())) {
                throw new BusinessException(ErrorCode.UPLOAD_SESSION_EXPIRED);
            }
            throw new BusinessException(ErrorCode.UPLOAD_NOT_WRITABLE);
        }
        appendAudit(locked.scope().actor(), "UPLOAD_PART_STORED", uploadId);
        return new PartCommit(result(part), candidateKey);
    }

    private LockedUpload lockUpload(CurrentUser supplied, SessionRow seed) {
        RequirementRow located = uploads.findRequirement(seed.requirementId());
        Scope scope = lockScope(supplied, located, seed.executionId());
        owner(seed, scope.actor());
        SessionRow session = requireSession(uploads.lockSession(seed.id()));
        owner(session, scope.actor());
        if (!session.taskId().equals(scope.task().id()) || !session.subtaskId().equals(scope.subtask().id())
                || !session.requirementId().equals(scope.requirement().id())
                || !session.evidenceId().equals(scope.requirement().evidenceId())
                || !session.executionId().equals(scope.execution().id())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        return new LockedUpload(scope, session);
    }

    private Scope lockScope(CurrentUser supplied, RequirementRow located, String executionId) {
        if (located == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        TaskRow task = requireTask(uploads.lockTask(located.taskId()));
        CurrentUser actor = permissions.currentActor(supplied, true);
        authorize(actor, task);
        if (located.subtaskId() == null) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        // Inspect foreign IDs before taking a lock so an invalid request cannot reverse another task's locks.
        ExecutionRow initial = uploads.findExecution(executionId);
        UploadValidation.invalid(initial == null || !located.subtaskId().equals(initial.subtaskId()));
        SubtaskRow subtask = uploads.lockSubtask(located.subtaskId());
        RequirementRow requirement = uploads.lockRequirement(located.id());
        if (subtask == null || requirement == null || !task.id().equals(subtask.taskId())
                || !task.id().equals(requirement.taskId()) || !subtask.id().equals(requirement.subtaskId())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        ExecutionRow execution = uploads.lockExecution(executionId);
        UploadValidation.invalid(execution == null || !subtask.id().equals(execution.subtaskId()));
        return new Scope(actor, task, subtask, requirement, execution);
    }

    private void authorize(CurrentUser actor, TaskRow task) {
        if (!OPERATOR.equals(actor.orgId()) || !actor.roles().contains(Role.OPERATOR_USER)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        permissions.requireTask(actor, task.id(), task.ownerOrgId(), task.operatorOrgId(), Role.OPERATOR_USER);
    }

    private static void owner(SessionRow session, CurrentUser actor) {
        if (!"USER".equals(session.actorType()) || !actor.id().equals(session.actorId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private static void active(Scope scope) {
        if ("COMPLETED".equals(scope.task().status())) {
            throw new BusinessException(ErrorCode.TASK_COMPLETED);
        }
        if (!Set.of("RUNNING", "WAITING_ACCEPTANCE").contains(scope.task().status())
                || !scope.execution().id().equals(scope.subtask().currentExecutionId())
                || !"RUNNING".equals(scope.execution().status())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void writable(LockedUpload locked) {
        active(locked.scope());
        if ("EXPIRED".equals(locked.session().status())) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_EXPIRED);
        }
        if (!"UPLOADING".equals(locked.session().status())) {
            throw new BusinessException(ErrorCode.UPLOAD_NOT_WRITABLE);
        }
        if (!locked.session().expiresAt().isAfter(uploads.databaseNow())) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_EXPIRED);
        }
    }

    private static PartPlan plan(SessionRow session, int partNo) {
        UploadValidation.invalid(partNo < 1 || partNo > session.partCount());
        long offset = ((long) partNo - 1) * session.partSize();
        return new PartPlan(session.id(), partNo, Math.min(session.partSize(), session.expectedSize() - offset),
                session.contentType());
    }

    private UploadProgress progress(SessionRow session) {
        List<UploadedPart> parts = new ArrayList<>();
        long uploadedSize = 0;
        for (PartRow part : uploads.listParts(session.id())) {
            uploadedSize = Math.addExact(uploadedSize, part.size());
            parts.add(new UploadedPart(part.partNo(), part.size(), part.sha256()));
        }
        if (uploadedSize > session.expectedSize()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        String status = "UPLOADING".equals(session.status()) && !session.expiresAt().isAfter(uploads.databaseNow())
                ? "EXPIRED" : session.status();
        return new UploadProgress(session.id(), session.requirementId(), session.executionId(), session.filename(),
                session.contentType(), session.expectedSize(), session.partSize(), session.partCount(), status,
                session.expiresAt(), List.copyOf(parts), uploadedSize, uploads.findVersionId(session.id()));
    }

    private void appendAudit(CurrentUser actor, String action, String sessionId) {
        String traceId = TraceContext.currentTraceId();
        if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,64}")) {
            traceId = UUID.randomUUID().toString();
        }
        one(audit.insertResourceSuccess(UUID.randomUUID().toString(), actor.id(), actor.orgId(), action,
                "UPLOAD_SESSION", sessionId, traceId));
    }

    private static void sameRequest(SessionRow existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }

    private static void samePart(PartRow existing, long size, String sha256) {
        if (existing.size() != size || !existing.sha256().equals(sha256)) {
            throw new BusinessException(ErrorCode.PART_CONTENT_CONFLICT);
        }
    }

    private static PartResult result(PartRow row) {
        return new PartResult(row.sessionId(), row.partNo(), row.size(), row.sha256());
    }

    private static SessionRow requireSession(SessionRow row) {
        if (row == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return row;
    }

    private static TaskRow requireTask(TaskRow row) {
        if (row == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return row;
    }

    private static void requireSupplied(CurrentUser actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private static void one(int count) {
        if (count != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private record Scope(CurrentUser actor, TaskRow task, SubtaskRow subtask,
                          RequirementRow requirement, ExecutionRow execution) {}
    private record LockedUpload(Scope scope, SessionRow session) {}
}
