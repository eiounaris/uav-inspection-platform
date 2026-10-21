package com.uav.inspection.common.error;

import com.uav.inspection.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode error = exception.errorCode();
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(error.name(), error.message(), null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled request exception", exception);
        ErrorCode error = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(error.name(), error.message(), null));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("HTTP request failed with status {}", status.value(), exception);
        }
        ErrorCode error = ErrorCode.forStatus(status.value());
        Object details = null;
        if (exception instanceof MethodArgumentNotValidException validation) {
            List<FieldViolation> errors = validation.getBindingResult().getFieldErrors().stream()
                    .map(field -> new FieldViolation(field.getField(), field.getDefaultMessage()))
                    .toList();
            details = new ValidationDetails(errors);
        }
        // Retain framework status and headers, including Allow for HTTP 405.
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception,
                ApiResponse.error(error.name(), error.message(), details), responseHeaders, status, request);
    }

    private record FieldViolation(String field, String message) {
    }

    private record ValidationDetails(List<FieldViolation> errors) {
    }
}
