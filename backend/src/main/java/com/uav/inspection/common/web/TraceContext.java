package com.uav.inspection.common.web;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class TraceContext {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    static final String REQUEST_ATTRIBUTE = TraceContext.class.getName() + ".traceId";

    private TraceContext() {
    }

    public static String currentTraceId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object traceId = attributes.getRequest().getAttribute(REQUEST_ATTRIBUTE);
            if (traceId instanceof String value) {
                return value;
            }
        }
        return MDC.get(MDC_KEY);
    }
}
