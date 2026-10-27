package com.uav.inspection.blockchain.anchor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AnchorRetryModels {
    private AnchorRetryModels() {}

    public record Request(@NotBlank String clientRequestId,
                          @NotBlank @Size(max=1000) String reason) {}

    /** Acceptance is durable intent; the existing anchor query remains the outcome source. */
    public record Receipt(String id,String submissionId,String versionId,String eventId,
                          int dispatchNo,Instant acceptedAt) {}
}
