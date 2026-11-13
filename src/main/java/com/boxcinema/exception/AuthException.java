package com.boxcinema.exception;

import org.springframework.http.HttpStatus;

/**
 * Domain exception carrying stable Specification V1.2 machine-readable error codes
 * and HTTP status mappings while preserving IllegalArgumentException compatibility
 * for Spring @Transactional(noRollbackFor = IllegalArgumentException.class).
 */
public class AuthException extends IllegalArgumentException {

    private final String errorCode;
    private final HttpStatus httpStatus;
    private final Long retryAfterSeconds;

    public AuthException(String errorCode, HttpStatus httpStatus, String message) {
        this(errorCode, httpStatus, message, null, null);
    }

    public AuthException(String errorCode, HttpStatus httpStatus, String message, Long retryAfterSeconds) {
        this(errorCode, httpStatus, message, retryAfterSeconds, null);
    }

    public AuthException(String errorCode, HttpStatus httpStatus, String message, Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = (httpStatus != null) ? httpStatus : HttpStatus.BAD_REQUEST;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static AuthException unauthorized(String errorCode, String message) {
        return new AuthException(errorCode, HttpStatus.UNAUTHORIZED, message);
    }

    public static AuthException forbidden(String errorCode, String message) {
        return new AuthException(errorCode, HttpStatus.FORBIDDEN, message);
    }

    public static AuthException conflict(String errorCode, String message) {
        return new AuthException(errorCode, HttpStatus.CONFLICT, message);
    }

    public static AuthException badRequest(String errorCode, String message) {
        return new AuthException(errorCode, HttpStatus.BAD_REQUEST, message);
    }

    public static AuthException rateLimited(String message, long retryAfterSeconds) {
        return new AuthException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, message, Math.max(1L, retryAfterSeconds));
    }
}

