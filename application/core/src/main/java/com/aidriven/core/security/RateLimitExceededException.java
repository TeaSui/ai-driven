package com.aidriven.core.security;

/**
 * Thrown when a user or ticket exceeds their allowed request rate.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
