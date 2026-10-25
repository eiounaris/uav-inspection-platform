package com.uav.inspection.device;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.device.DeviceModels.ReportRequest;
import com.uav.inspection.device.DeviceModels.ReportResult;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/device/reports")
public class DeviceReportController {
    private final DeviceService devices;
    public DeviceReportController(DeviceService devices){this.devices=devices;}
    @PostMapping public ApiResponse<ReportResult> report(@AuthenticationPrincipal CurrentDevice actor,@Valid @RequestBody ReportRequest request) {
        return ApiResponse.success(devices.report(actor,request));
    }
}
