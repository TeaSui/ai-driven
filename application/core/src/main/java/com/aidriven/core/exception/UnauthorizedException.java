package com.aidriven.core.exception;

/**
 * Exception thrown when authentication fails (HTTP 401).
 * Indicates invalid credentials, expired tokens, or missing authentication.
 */
public class UnauthorizedException extends HttpClientException {

    public UnauthorizedException(String message, String responseBody) {
        super(401, message, responseBody);
    }

    public UnauthorizedException(String service) {
        super(401, String.format("Authentication failed for %s. Check credentials.", service), null);
    }
}
