package com.uav.inspection.evidence.upload;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.evidence.upload.UploadModels.CreateUploadRequest;
import com.uav.inspection.evidence.upload.UploadModels.PartResult;
import com.uav.inspection.evidence.upload.UploadModels.UploadProgress;
import com.uav.inspection.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@Profile("database & storage")
@RequestMapping("/api/v1/upload-sessions")
public class UploadController {
    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping
    public ApiResponse<UploadProgress> create(@AuthenticationPrincipal CurrentUser actor,
                                               @Valid @RequestBody CreateUploadRequest request) {
        return ApiResponse.success(uploads.create(actor, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<UploadProgress> get(@AuthenticationPrincipal CurrentUser actor,
                                           @PathVariable String id) {
        return ApiResponse.success(uploads.get(actor, id));
    }

    @PutMapping(value = "/{id}/parts/{partNo}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<PartResult> putPart(@AuthenticationPrincipal CurrentUser actor,
                                            @PathVariable String id, @PathVariable int partNo,
                                            HttpServletRequest request) {
        try {
            return ApiResponse.success(uploads.uploadPart(actor, id, partNo, request.getInputStream(),
                    request.getContentLengthLong()));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
