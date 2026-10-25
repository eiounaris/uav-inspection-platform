package com.uav.inspection.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ReviewModels {
    private ReviewModels() {}

    public record CreateIssueRequest(@NotBlank String versionId,
                                     @NotBlank @Size(max=4000) String description) {}

    public record IssueView(String id,String taskId,String versionId,String description,
                            String createdBy,Instant createdAt) {}

    public record IssuePage(List<IssueView> items,int page,int pageSize,long total) {}

    public record ReviewRequest(@NotBlank String decision,@Size(max=1000) String reason,
                                @NotBlank String expectedReviewStatus) {}

    public record ReviewView(String id,String versionId,String decision,String reason,
                             String reviewerId,Instant createdAt) {}

    public record ReviewState(String versionId,String reviewStatus,ReviewView review) {}
}
