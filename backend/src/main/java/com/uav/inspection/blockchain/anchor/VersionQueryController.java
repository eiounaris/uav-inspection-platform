package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.anchor.AnchorViews.AnchorView;
import com.uav.inspection.blockchain.anchor.AnchorViews.VersionView;
import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("database")
@RequestMapping("/api/v1/evidence-versions")
public class VersionQueryController {
    private final VersionQueryService queries;
    public VersionQueryController(VersionQueryService queries) { this.queries = queries; }

    @GetMapping("/{id}")
    public ApiResponse<VersionView> version(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id) {
        return ApiResponse.success(queries.version(actor, id));
    }

    @GetMapping("/{id}/anchor")
    public ApiResponse<AnchorView> anchor(@AuthenticationPrincipal CurrentUser actor, @PathVariable String id) {
        return ApiResponse.success(queries.anchor(actor, id));
    }
}
