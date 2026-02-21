package com.teasui.crm.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception for all microservice business logic errors.
 */
@Getter
public class ServiceException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ServiceException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public ServiceException(String message, HttpStatus status) {
        this(message, status, status.name());
    }

    public static ServiceException notFound(String resource, String id) {
        return new ServiceException(
                String.format("%s with id '%s' not found", resource, id),
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND"
        );
    }

    public static ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    public static ServiceException unauthorized(String message) {
        return new ServiceException(message, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    public static ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    public static ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT, "CONFLICT");
    }
}
