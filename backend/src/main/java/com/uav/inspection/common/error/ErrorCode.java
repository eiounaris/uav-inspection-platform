package com.uav.inspection.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "请先登录"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无操作权限"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "数据状态已变更，请刷新后重试"),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "上传内容超出大小限制"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂不可用"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时无法处理请求");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    public static ErrorCode forStatus(int status) {
        for (ErrorCode error : values()) {
            if (error.status.value() == status) {
                return error;
            }
        }
        return status >= 500 ? INTERNAL_ERROR : INVALID_ARGUMENT;
    }
}
