package com.uav.inspection.evidence.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class UploadModels {
    private UploadModels() {}

    public record CreateUploadRequest(@NotBlank String clientRequestId, @NotBlank String requirementId,
                                       @NotBlank String executionId, @NotBlank @Size(max = 255) String filename,
                                       @NotBlank @Size(max = 100) String contentType, @NotNull @Positive Long size) {}
    public record UploadedPart(int partNo, long size, String sha256) {}
    public record UploadProgress(String uploadId, String requirementId, String executionId, String filename,
                                   String contentType, long size, int partSize, int partCount,
                                   String processingStatus, Instant expiresAt, List<UploadedPart> uploadedParts,
                                   long uploadedSize, String versionId) {}
    public record PartResult(String uploadId, int partNo, long size, String sha256) {}

    /** Internal only: neither a storage key nor a persistence record is an HTTP response. */
    public record PartPlan(String uploadId, int partNo, long expectedSize, String contentType) {}
    public record PartCommit(PartResult result, String storedObjectKey) {}
}
