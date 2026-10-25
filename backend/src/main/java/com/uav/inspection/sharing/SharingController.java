package com.uav.inspection.sharing;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import com.uav.inspection.sharing.SharingModels.*;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/access-grants")
public class SharingController {
    private final SharingService sharing;
    public SharingController(SharingService sharing) { this.sharing=sharing; }

    @PostMapping public ApiResponse<GrantView> apply(@AuthenticationPrincipal CurrentUser actor,@Valid @RequestBody ApplyRequest request) {
        return ApiResponse.success(sharing.apply(actor,request));
    }
    @PostMapping("/{id}/decision") public ApiResponse<GrantView> decide(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable String id,@Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success(sharing.decide(actor,id,request));
    }
    @PostMapping("/{id}/revoke") public ApiResponse<GrantView> revoke(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable String id,@Valid @RequestBody RevokeRequest request) {
        return ApiResponse.success(sharing.revoke(actor,id,request));
    }
    @GetMapping public ApiResponse<GrantPage> list(@AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required=false) Boolean mine,@RequestParam(required=false) String taskId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(sharing.list(actor,mine,taskId,page,pageSize));
    }
}
