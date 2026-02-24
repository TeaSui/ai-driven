package com.aidriven.core.resilience;

/**
 * Exception thrown when operation deadline is approaching.
 * Allows for graceful degradation before hard deadline.
 *
 * @since 1.0
 */
public class DeadlineApproachingException extends Exception {

    public DeadlineApproachingException(String message) {
        super(message);
    }

    public DeadlineApproachingException(String message, Throwable cause) {
        super(message, cause);
    }
}

