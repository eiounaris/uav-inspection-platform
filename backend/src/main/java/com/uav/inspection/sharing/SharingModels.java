package com.uav.inspection.sharing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class SharingModels {
    private SharingModels() {}
    public record ApplyRequest(@NotBlank String clientRequestId,@NotBlank String versionId,
                                @NotBlank @Size(max=1000) String reason,@NotNull Instant requestedValidUntil) {}
    public record DecisionRequest(@NotBlank String decision,Instant validUntil,@Size(max=1000) String reason,
                                   @NotNull @PositiveOrZero Long expectedLockVersion) {}
    public record RevokeRequest(@NotBlank @Size(max=1000) String reason,
                                 @NotNull @PositiveOrZero Long expectedLockVersion) {}
    /** Contains request/decision metadata only; it never grants access to the parent task or file. */
    public record GrantView(String id,String versionId,String applicantId,String clientRequestId,String status,
                             String reason,Instant requestedValidUntil,String approverId,String decisionReason,
                             Instant decidedAt,Instant validUntil,String revokedBy,String revokeReason,Instant revokedAt,
                             long lockVersion,Instant createdAt,Instant updatedAt) {}
    public record GrantPage(List<GrantView> items,int page,int pageSize,long total) {}
}
