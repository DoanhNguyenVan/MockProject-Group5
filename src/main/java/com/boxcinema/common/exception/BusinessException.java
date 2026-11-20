package com.boxcinema.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Map<String, Object> details;

    public BusinessException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null);
    }

    public BusinessException(HttpStatus status, String errorCode, String message, Map<String, Object> details) {
        super(message);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
        this.errorCode = errorCode != null ? errorCode : "BUSINESS_ERROR";
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
