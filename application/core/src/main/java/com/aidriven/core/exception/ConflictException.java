package com.aidriven.core.exception;

/**
 * Exception thrown when a resource conflict occurs (HTTP 409).
 * Common scenarios include:
 * - Attempting to create a branch that already exists
 * - Concurrent modification of a resource
 * - Duplicate resource creation
 */
public class ConflictException extends HttpClientException {

    private static final int HTTP_CONFLICT = 409;

    public ConflictException(String message, String responseBody) {
        super(HTTP_CONFLICT, message, responseBody);
    }

    public ConflictException(String message, String responseBody, Throwable cause) {
        super(HTTP_CONFLICT, message, responseBody, cause);
    }
}
