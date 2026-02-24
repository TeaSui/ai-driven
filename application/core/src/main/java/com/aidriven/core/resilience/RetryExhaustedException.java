package com.aidriven.core.resilience;

/**
 * Exception thrown when all retry attempts are exhausted.
 *
 * @since 1.0
 */
public class RetryExhaustedException extends Exception {

    /**
     * Creates exception with message and cause.
     *
     * @param message Description
     * @param cause Original exception from last attempt
     */
    public RetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates exception with message only.
     *
     * @param message Description
     */
    public RetryExhaustedException(String message) {
        super(message);
    }
}

