package com.boxcinema.common.exception;

import com.boxcinema.auth.service.AuthRateLimitService;
import com.boxcinema.common.constant.ApiErrorCodes;
import com.boxcinema.common.dto.ApiResponse;
import com.boxcinema.refund.service.RefundRateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized exception handler for REST API controllers.
 */
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RefundRateLimitService.RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRefundRateLimitExceeded(RefundRateLimitService.RateLimitExceededException ex) {
        long retryAfter = Math.max(1L, ex.getRetryAfterSeconds());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("success", false);
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        body.put("retryAfterSeconds", retryAfter);
        body.put("limitScope", ex.getLimitScope());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .body(body);
    }

    @ExceptionHandler(AuthRateLimitService.RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleAuthRateLimitExceeded(AuthRateLimitService.RateLimitExceededException ex) {
        long retryAfter = Math.max(1L, ex.getRetryAfterSeconds());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("success", false);
        body.put("errorCode", "RATE_LIMIT_EXCEEDED");
        body.put("message", ex.getMessage());
        body.put("retryAfterSeconds", retryAfter);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.getStatus().value());
        body.put("success", false);
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        if (ex.getDetails() != null && !ex.getDetails().isEmpty()) {
            body.putAll(ex.getDetails());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus());
        if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            Object retryObj = ex.getDetails() != null ? ex.getDetails().get("retryAfterSeconds") : null;
            long retryAfter = (retryObj instanceof Number num) ? Math.max(1L, num.longValue()) : 60L;
            body.putIfAbsent("retryAfterSeconds", retryAfter);
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        }
        return builder.body(body);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), ApiErrorCodes.RESOURCE_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED",
                        ex.getMessage() != null ? ex.getMessage() : "Access denied."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        String firstMessage = "Validation failed";
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        if (!fieldErrors.isEmpty()) {
            firstMessage = fieldErrors.values().iterator().next();
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ApiErrorCodes.VALIDATION_FAILED, firstMessage, fieldErrors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ApiErrorCodes.INVALID_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(HttpStatus.CONFLICT.value(), "INVALID_STATE", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(HttpStatus.CONFLICT.value(), "DATA_INTEGRITY_CONFLICT",
                        "The operation conflicts with existing or referenced records."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unhandled REST API error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), ApiErrorCodes.INTERNAL_ERROR,
                        "An unexpected server error occurred."));
    }
}
