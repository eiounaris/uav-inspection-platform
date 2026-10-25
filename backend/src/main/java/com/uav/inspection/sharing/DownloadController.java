package com.uav.inspection.sharing;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.identity.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("database & storage")
public class DownloadController {
    private final DownloadService downloads;
    public DownloadController(DownloadService downloads) { this.downloads=downloads; }

    @PostMapping("/api/v1/evidence-versions/{id}/download-url")
    public ResponseEntity<ApiResponse<DownloadService.DownloadUrl>> issue(@AuthenticationPrincipal CurrentUser actor,
                                                                        @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(downloads.issue(actor,id)));
    }
}
