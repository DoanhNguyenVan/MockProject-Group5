package com.boxcinema.common.exception;

import com.boxcinema.common.constant.ApiErrorCodes;
import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, ApiErrorCodes.RESOURCE_NOT_FOUND, message);
    }

    public ResourceNotFoundException(String errorCode, String message) {
        super(HttpStatus.NOT_FOUND, errorCode, message);
    }
}
