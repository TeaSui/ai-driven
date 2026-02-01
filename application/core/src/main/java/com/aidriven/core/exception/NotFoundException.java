package com.aidriven.core.exception;

/**
 * Exception thrown when a resource is not found (HTTP 404).
 */
public class NotFoundException extends HttpClientException {

    public NotFoundException(String message, String responseBody) {
        super(404, message, responseBody);
    }

    public NotFoundException(String resourceType, String resourceId, boolean useResourceFormat) {
        super(404, String.format("%s not found: %s", resourceType, resourceId), null);
    }
}
