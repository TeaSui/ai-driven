package com.aidriven.core.exception;

/**
 * Base exception for HTTP client errors (4xx status codes).
 */
public class HttpClientException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public HttpClientException(int statusCode, String message, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public HttpClientException(int statusCode, String message, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    @Override
    public String toString() {
        return String.format("%s: HTTP %d - %s", getClass().getSimpleName(), statusCode, getMessage());
    }
}
