package com.aidriven.core.exception;

/**
 * Exception thrown when a service is temporarily unavailable (HTTP 503).
 */
public class ServiceUnavailableException extends HttpClientException {

    public ServiceUnavailableException(String message, String responseBody) {
        super(503, message, responseBody);
    }

    public ServiceUnavailableException(String service) {
        super(503, String.format("%s is temporarily unavailable. Please retry later.", service), null);
    }
}
