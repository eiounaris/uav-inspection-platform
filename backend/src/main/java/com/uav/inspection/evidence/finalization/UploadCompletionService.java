package com.uav.inspection.evidence.finalization;

import com.uav.inspection.audit.persistence.AuditMapper;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.web.TraceContext;
import com.uav.inspection.evidence.finalization.persistence.FinalizationMapper;
import com.uav.inspection.evidence.upload.UploadModels.UploadProgress;
import com.uav.inspection.evidence.upload.UploadTransactions;
import com.uav.inspection.evidence.upload.persistence.UploadMapper;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.PartRow;
import com.uav.inspection.evidence.upload.persistence.UploadMapper.SessionRow;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.device.CurrentDevice;
import com.uav.inspection.messaging.OutboxWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Profile("database & storage & messaging")
public class UploadCompletionService {
    private final UploadTransactions uploads;
    private final UploadMapper parts;
    private final FinalizationMapper finalizations;
    private final OutboxWriter outbox;
    private final AuditMapper audit;

    public UploadCompletionService(UploadTransactions uploads, UploadMapper parts,
                                   FinalizationMapper finalizations, OutboxWriter outbox, AuditMapper audit) {
        this.uploads = uploads;
        this.parts = parts;
        this.finalizations = finalizations;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional
    public UploadProgress complete(CurrentUser actor, String uploadId) {
        SessionRow session = uploads.lockForCompletion(actor, uploadId);
        completeLocked(session);
        return uploads.get(actor, uploadId);
    }

    @Transactional
    public UploadProgress completeDevice(CurrentDevice actor, String uploadId) {
        SessionRow session = uploads.lockForCompletionDevice(actor, uploadId);
        completeLocked(session);
        return uploads.getDevice(actor, uploadId);
    }

    private void completeLocked(SessionRow session) {
        String uploadId = session.id();
        if ("UPLOADING".equals(session.status())) {
            requireCompleteParts(session, parts.listParts(uploadId));
            if (finalizations.freeze(uploadId, session.lockVersion()) != 1) {
                if (!session.expiresAt().isAfter(parts.databaseNow())) {
                    throw new BusinessException(ErrorCode.UPLOAD_SESSION_EXPIRED);
                }
                throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            }
            String trace = safeTrace();
            outbox.enqueue("FILE_FINALIZE_REQUESTED", uploadId, trace);
            one("DEVICE".equals(session.actorType()) ? audit.insertDeviceResourceSuccess(UUID.randomUUID().toString(), session.actorId(), session.submitterOrgId(),
                    "UPLOAD_COMPLETION_REQUESTED", "UPLOAD_SESSION", uploadId, trace) : audit.insertResourceSuccess(UUID.randomUUID().toString(), session.actorId(), session.submitterOrgId(),
                    "UPLOAD_COMPLETION_REQUESTED", "UPLOAD_SESSION", uploadId, trace));
        }
    }

    static void requireCompleteParts(SessionRow session, List<PartRow> parts) {
        if (parts.size() != session.partCount()) {
            throw new BusinessException(ErrorCode.UPLOAD_PARTS_INCOMPLETE);
        }
        long total = 0;
        for (int i = 0; i < parts.size(); i++) {
            PartRow part = parts.get(i);
            long expected = Math.min(session.partSize(), session.expectedSize() - (long) i * session.partSize());
            if (part.partNo() != i + 1 || part.size() != expected) {
                throw new BusinessException(ErrorCode.UPLOAD_PARTS_INCOMPLETE);
            }
            total = Math.addExact(total, part.size());
        }
        if (total != session.expectedSize()) {
            throw new BusinessException(ErrorCode.UPLOAD_PARTS_INCOMPLETE);
        }
    }

    private static String safeTrace() {
        String trace = TraceContext.currentTraceId();
        return trace != null && trace.matches("[A-Za-z0-9_-]{1,64}") ? trace : UUID.randomUUID().toString();
    }

    private static void one(int value) {
        if (value != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
}
