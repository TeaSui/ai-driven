package com.aidriven.core.security;

/**
 * Enforces rate limits based on arbitrary keys (e.g., userId, ticketKey, or
 * composite).
 */
public interface RateLimiter {

    /**
     * Attempts to consume 1 token from the bucket identified by the given key.
     * 
     * @param key                Identifies the rate limit bucket (e.g.,
     *                           "user:U12345:ticket:CRM-88")
     * @param maxRequestsPerHour Maximum allowed requests per hour for this key.
     * @throws RateLimitExceededException if the limit has been reached.
     */
    void consumeOrThrow(String key, int maxRequestsPerHour) throws RateLimitExceededException;
}
