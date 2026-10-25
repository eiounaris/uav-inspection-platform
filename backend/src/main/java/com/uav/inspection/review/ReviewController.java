package com.uav.inspection.review;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.review.ReviewModels.*;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1")
public class ReviewController {
    private final ReviewService reviews;
    public ReviewController(ReviewService reviews) { this.reviews=reviews; }

    @PostMapping("/issues")
    public ApiResponse<IssueView> createIssue(@AuthenticationPrincipal CurrentUser actor,
                                             @Valid @RequestBody CreateIssueRequest request) {
        return ApiResponse.success(reviews.createIssue(actor,request));
    }

    @GetMapping("/evidence-versions/{id}/issues")
    public ApiResponse<IssuePage> issues(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(reviews.issues(actor,id,page,pageSize));
    }

    @PostMapping("/evidence-versions/{id}/review")
    public ApiResponse<ReviewState> review(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id,
                                          @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(reviews.review(actor,id,request));
    }

    @GetMapping("/evidence-versions/{id}/review")
    public ApiResponse<ReviewState> review(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id) {
        return ApiResponse.success(reviews.review(actor,id));
    }
}
