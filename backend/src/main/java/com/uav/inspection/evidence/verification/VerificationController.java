package com.uav.inspection.evidence.verification;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.verification.VerificationModels.VerificationPage;
import com.uav.inspection.evidence.verification.VerificationModels.VerificationView;
import com.uav.inspection.identity.CurrentUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Semaphore;

@RestController
@Profile("database & storage & ledger")
@RequestMapping("/api/v1/evidence-versions/{id}/verifications")
public class VerificationController {
    private final VerificationService verifications;
    private final Semaphore receptions;

    public VerificationController(VerificationService verifications, VerificationSettings settings) {
        this.verifications = verifications;
        receptions = new Semaphore(settings.concurrency(), true);
    }

    @PostMapping
    public ApiResponse<VerificationView> verify(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable String id, HttpServletRequest request) {
        // No @RequestParam/@RequestBody/@RequestPart here: resolving them can parse multipart bytes
        // before checking whether this user is allowed to access the named evidence version.
        verifications.authorize(actor, id);
        String query = request.getQueryString();
        if (!"source=STORED".equals(query) && !"source=COPY".equals(query)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if ("source=STORED".equals(query)) {
            if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null
                    || request.getContentType() != null) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            return ApiResponse.success(verifications.verifyStored(actor, id));
        }
        if (!isMultipartForm(request) || !(request instanceof MultipartHttpServletRequest multipart)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        if (!receptions.tryAcquire()) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        Collection<Part> parts = null;
        try {
            var files = multipart.getMultiFileMap(); // Lazy parsing starts only after authorization/admission.
            parts = request.getParts();
            if (parts.size() != 1 || files.size() != 1 || files.get("file") == null
                    || files.get("file").size() != 1) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            MultipartFile file = files.getFirst("file");
            if (file == null) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            return ApiResponse.success(verifications.verifyCopy(actor, id, file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), file::getInputStream));
        } catch (ServletException | IOException malformed) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        } finally {
            if (parts != null) for (Part part : parts) {
                try { part.delete(); }
                catch (IOException failure) {
                    LoggerFactory.getLogger(VerificationController.class)
                            .warn("Verification request temporary cleanup deferred: {}", failure.getClass().getSimpleName());
                }
            }
            receptions.release();
        }
    }

    @GetMapping
    public ApiResponse<VerificationPage> history(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable String id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(verifications.history(actor, id, page, pageSize));
    }

    private boolean isMultipartForm(HttpServletRequest request) {
        try {
            return request.getContentType() != null && MediaType.MULTIPART_FORM_DATA.isCompatibleWith(
                    MediaType.parseMediaType(request.getContentType()));
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
