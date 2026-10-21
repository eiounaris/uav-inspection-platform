package com.uav.inspection.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        request.setAttribute(TraceContext.REQUEST_ATTRIBUTE, traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        String previous = MDC.get(TraceContext.MDC_KEY);
        MDC.put(TraceContext.MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(TraceContext.MDC_KEY);
            } else {
                MDC.put(TraceContext.MDC_KEY, previous);
            }
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private String resolveTraceId(HttpServletRequest request) {
        if (request.getAttribute(TraceContext.REQUEST_ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String supplied = request.getHeader(TraceContext.HEADER);
        return supplied != null && VALID_TRACE_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
    }
}
