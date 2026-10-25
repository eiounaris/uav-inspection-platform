package com.uav.inspection.evidence.finalization;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.evidence.upload.UploadModels.UploadProgress;
import com.uav.inspection.device.CurrentDevice;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database & storage & messaging")
@RequestMapping("/api/v1/device/upload-sessions")
public class DeviceUploadCompletionController {
    private final UploadCompletionService completion;

    public DeviceUploadCompletionController(UploadCompletionService completion) { this.completion = completion; }

    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<UploadProgress> complete(@AuthenticationPrincipal CurrentDevice actor, @PathVariable String id) {
        return ApiResponse.success(completion.completeDevice(actor, id));
    }
}
