package com.aidriven.core.exception;

/**
 * Exception thrown when rate limit is exceeded (HTTP 429).
 * Contains information about retry timing when available.
 */
public class RateLimitException extends HttpClientException {

    private final Long retryAfterSeconds;

    public RateLimitException(String message, String responseBody, Long retryAfterSeconds) {
        super(429, message, responseBody);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public RateLimitException(String service) {
        super(429, String.format("Rate limit exceeded for %s. Please retry later.", service), null);
        this.retryAfterSeconds = null;
    }

    public RateLimitException(String service, long retryAfterSeconds) {
        super(429, String.format("Rate limit exceeded for %s. Retry after %d seconds.", service, retryAfterSeconds), null);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns the recommended retry delay in seconds, if available from the API response.
     */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Alias for {@link #getRetryAfterSeconds()}.
     */
    public Long getRetryAfter() {
        return retryAfterSeconds;
    }

    /**
     * Checks if retry timing information is available.
     */
    public boolean hasRetryAfter() {
        return retryAfterSeconds != null && retryAfterSeconds > 0;
    }
}
