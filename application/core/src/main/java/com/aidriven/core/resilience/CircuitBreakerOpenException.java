package com.aidriven.core.resilience;

/**
 * Exception thrown when circuit breaker is open.
 * Indicates that the service is unavailable and should not be called.
 *
 * @since 1.0
 */
public class CircuitBreakerOpenException extends RuntimeException {

    /**
     * Creates exception with message.
     *
     * @param message Description of the open circuit
     */
    public CircuitBreakerOpenException(String message) {
        super(message);
    }

    /**
     * Creates exception with message and cause.
     *
     * @param message Description of the open circuit
     * @param cause Original exception
     */
    public CircuitBreakerOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}

