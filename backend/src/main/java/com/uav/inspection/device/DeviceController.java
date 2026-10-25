package com.uav.inspection.device;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.device.DeviceModels.*;
import com.uav.inspection.identity.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/drones")
public class DeviceController {
    private final DeviceService devices;
    public DeviceController(DeviceService devices){this.devices=devices;}
    @PostMapping public ApiResponse<CreatedDevice> create(@AuthenticationPrincipal CurrentUser actor,@Valid @RequestBody CreateDeviceRequest request) {
        return ApiResponse.success(devices.create(actor,request));
    }
    @GetMapping public ApiResponse<DevicePage> list(@AuthenticationPrincipal CurrentUser actor,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(devices.list(actor,page,pageSize));
    }
    @GetMapping("/{id}") public ApiResponse<DeviceView> get(@AuthenticationPrincipal CurrentUser actor,@PathVariable String id) {
        return ApiResponse.success(devices.get(actor,id));
    }
    @PatchMapping("/{id}/enabled") public ApiResponse<DeviceView> setEnabled(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable String id,@Valid @RequestBody EnableDeviceRequest request) {
        return ApiResponse.success(devices.setEnabled(actor,id,request));
    }
}
