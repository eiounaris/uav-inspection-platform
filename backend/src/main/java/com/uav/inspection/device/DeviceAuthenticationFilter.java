package com.uav.inspection.device;

import com.uav.inspection.common.api.ApiResponse;
import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;

/** Constructed only inside the dedicated device SecurityFilterChain; never a global servlet filter. */
public final class DeviceAuthenticationFilter extends OncePerRequestFilter {
    private final DeviceAuthenticationService devices;
    private final JsonMapper json;

    public DeviceAuthenticationFilter(DeviceAuthenticationService devices,JsonMapper json) {
        this.devices=devices; this.json=json;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api/v1/device") ||path.startsWith("/api/v1/device/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        CurrentDevice principal;
        try {
            principal=devices.authenticate(singleHeader(request,"X-Device-Id"),singleHeader(request,"X-Device-Token"));
        } catch(BusinessException rejected) {
            reject(response,rejected.errorCode()==ErrorCode.DEPENDENCY_UNAVAILABLE?ErrorCode.DEPENDENCY_UNAVAILABLE:ErrorCode.UNAUTHORIZED);
            return;
        } catch(DataAccessException | TransactionException unavailable) {
            // Also covers transaction acquisition/completion outside the service method body.
            reject(response,ErrorCode.DEPENDENCY_UNAVAILABLE); return;
        }
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVICE"))));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request,response); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private static String singleHeader(HttpServletRequest request,String name) {
        Enumeration<String> values=request.getHeaders(name);
        if(values==null ||!values.hasMoreElements()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        String value=values.nextElement();
        if(values.hasMoreElements()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return value;
    }

    private void reject(HttpServletResponse response,ErrorCode error) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(error.status().value()); response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        if(error==ErrorCode.UNAUTHORIZED) response.setHeader("WWW-Authenticate","Device");
        json.writeValue(response.getOutputStream(),ApiResponse.error(error.name(),error.message(),null));
    }
}
