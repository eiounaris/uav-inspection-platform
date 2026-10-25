package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.anchor.AnchorViews.AnchorView;
import com.uav.inspection.blockchain.anchor.AnchorViews.VersionView;
import com.uav.inspection.blockchain.anchor.AnchorViews.VersionPage;
import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@Profile("database")
@RequestMapping("/api/v1/evidence-versions")
public class VersionQueryController {
    private final VersionQueryService queries;
    public VersionQueryController(VersionQueryService queries) { this.queries = queries; }

    @GetMapping
    public ApiResponse<VersionPage> list(@AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required=false) String taskId, @RequestParam(required=false) String requirementId,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(queries.list(actor, taskId, requirementId, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<VersionView> version(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id) {
        return ApiResponse.success(queries.version(actor, id));
    }

    @GetMapping("/{id}/anchor")
    public ApiResponse<AnchorView> anchor(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id) {
        return ApiResponse.success(queries.anchor(actor, id));
    }
}
