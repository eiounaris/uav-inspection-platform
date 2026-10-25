package com.uav.inspection.blockchain.anchor;

import java.time.Instant;
import java.util.List;

/** Safe business views. Serialized Fabric checkpoints and private object keys are never included. */
public final class AnchorViews {
    private AnchorViews() {}

    public record AnchorView(String submissionId, String versionId, String status,
                             String confirmedTxId, String confirmedBy, boolean retryable, int retryCount,
                             Instant nextActionAt, String lastError, String transactionId,
                             String attemptPhase, Integer validationCode, Long blockNumber) {}

    public record VersionView(String id, String evidenceId, int versionNo, String requirementId,
                              String taskId, String subtaskId, String executionId, String filename,
                              String contentType, long size, String sha256, String reviewStatus,
                              Instant createdAt, AnchorView anchor) {}

    public record VersionListItem(String id, String evidenceId, int versionNo, String requirementId,
                                  String taskId, String subtaskId, String executionId, String filename,
                                  String contentType, long size, String sha256, String reviewStatus,
                                  Instant createdAt, String anchorStatus, String confirmedTxId) {}

    public record VersionPage(List<VersionListItem> items, int page, int pageSize, long total) {}
}
