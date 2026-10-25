package com.uav.inspection.device;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class DeviceModels {
    private DeviceModels() {}

    public record CreateDeviceRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String code) {}
    public record EnableDeviceRequest(@NotNull Boolean enabled,@NotNull @PositiveOrZero Long expectedLockVersion) {}
    public record DeviceView(String id,String operatorOrgId,String code,boolean enabled,Instant lastSeenAt,
                             boolean online,long lockVersion,Instant createdAt,Instant updatedAt) {}
    public record CreatedDevice(DeviceView device,String deviceToken) {
        @Override public String toString(){return "CreatedDevice[device="+device+", deviceToken=<redacted>]";}
    }
    public record DevicePage(List<DeviceView> items,int page,int pageSize,long total) {}
    public record ReportRequest(@NotBlank String bootId,@NotNull @PositiveOrZero @Max(9007199254740991L) Long seq,
                                @NotBlank String eventId,@NotBlank String executionId,
                                @NotBlank @Pattern(regexp="HEARTBEAT|PROGRESS") String type,Integer progress) {}
    public record ReportResult(String id,String droneId,String eventId,String bootId,long seq,String executionId,
                               String type,Integer progress,int executionProgress,Instant receivedAt,boolean duplicate) {}
}
