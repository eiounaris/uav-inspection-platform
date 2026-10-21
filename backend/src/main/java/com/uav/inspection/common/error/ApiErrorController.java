package com.uav.inspection.common.error;

import com.uav.inspection.common.api.ApiResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 覆盖 Servlet 错误分派，避免返回 Boot 默认错误结构或 HTML 页面。 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("${spring.web.error.path:/error}")
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
        ErrorCode error = ErrorCode.forStatus(status);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(error.name(), error.message(), null));
    }
}
