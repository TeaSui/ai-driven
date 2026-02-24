package com.aidriven.spi.exception;

/**
 * Exception thrown when domain validation fails.
 *
 * @since 1.0
 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
