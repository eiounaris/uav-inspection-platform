package com.uav.inspection.blockchain.anchor;

import com.uav.inspection.blockchain.anchor.AnchorRetryModels.Receipt;
import com.uav.inspection.blockchain.anchor.AnchorRetryModels.Request;
import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database & storage & messaging & ledger")
@RequestMapping("/api/v1/evidence-versions")
public class AnchorRetryController {
    private final AnchorRetryService service;

    public AnchorRetryController(AnchorRetryService service) { this.service=service; }

    @PostMapping("/{id}/anchor-retries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Receipt> request(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id,
                                       @Valid @RequestBody Request request) {
        return ApiResponse.success(service.request(actor,id,request));
    }
}
