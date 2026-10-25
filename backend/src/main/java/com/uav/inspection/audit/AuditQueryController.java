package com.uav.inspection.audit;

import com.uav.inspection.audit.AuditQueryModels.AuditPage;
import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/tasks/{id}/audit-logs")
public class AuditQueryController {
    private final AuditQueryService queries;
    public AuditQueryController(AuditQueryService queries) { this.queries=queries; }

    @GetMapping public ApiResponse<AuditPage> page(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String action,@RequestParam(required=false) String resourceType,
            @RequestParam(required=false) String traceId) {
        return ApiResponse.success(queries.page(actor,id,page,pageSize,action,resourceType,traceId));
    }
}
