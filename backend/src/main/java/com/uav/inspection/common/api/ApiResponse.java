package com.uav.inspection.common.api;

import com.uav.inspection.common.web.TraceContext;

public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "成功", data, TraceContext.currentTraceId());
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, TraceContext.currentTraceId());
    }
}
