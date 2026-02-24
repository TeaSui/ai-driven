package com.aidriven.core.resilience;

/**
 * Exception thrown when operation deadline has been exceeded.
 *
 * @since 1.0
 */
public class DeadlineExceededException extends Exception {

    public DeadlineExceededException(String message) {
        super(message);
    }

    public DeadlineExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}

