package com.aidriven.core.exception;

/**
 * Exception thrown when access is forbidden (HTTP 403).
 * Indicates the user is authenticated but lacks permission for the requested
 * operation.
 */
public class ForbiddenException extends HttpClientException {

    public ForbiddenException(String message, String responseBody) {
        super(403, message, responseBody);
    }

    public ForbiddenException(String service, String resource, boolean useResourceFormat) {
        super(403, String.format("Access denied to %s in %s. Check permissions.", resource, service), null);
    }
}
