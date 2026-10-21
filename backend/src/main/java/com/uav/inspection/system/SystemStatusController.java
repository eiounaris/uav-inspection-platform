package com.uav.inspection.system;

import com.uav.inspection.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final Clock clock;
    private final String application;

    public SystemStatusController(Clock clock, @Value("${spring.application.name}") String application) {
        this.clock = clock;
        this.application = application;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatus> status() {
        return ApiResponse.success(new SystemStatus(application, "RUNNING", Instant.now(clock)));
    }

    public record SystemStatus(String application, String status, Instant timestamp) {
    }
}
