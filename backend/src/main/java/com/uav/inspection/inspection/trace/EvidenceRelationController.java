package com.uav.inspection.inspection.trace;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.inspection.trace.EvidenceRelationModels.EvidenceRelationPage;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
public class EvidenceRelationController {
    private final EvidenceRelationService relations;
    public EvidenceRelationController(EvidenceRelationService relations) { this.relations=relations; }

    @GetMapping("/api/v1/tasks/{id}/evidence-relations")
    public ApiResponse<EvidenceRelationPage> list(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id,
            @RequestParam(required=false) String requirementId,@RequestParam(required=false) String executionId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(relations.list(actor,id,requirementId,executionId,page,pageSize));
    }
}
