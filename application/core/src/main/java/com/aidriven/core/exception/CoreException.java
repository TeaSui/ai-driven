package com.aidriven.core.exception;

/**
 * Base exception class for all core domain exceptions.
 * Provides common functionality for exception handling across the application.
 */
public abstract class CoreException extends RuntimeException {

    private final String errorCode;

    protected CoreException(String message) {
        super(message);
        this.errorCode = deriveErrorCode();
    }

    protected CoreException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = deriveErrorCode();
    }

    protected CoreException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected CoreException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    private String deriveErrorCode() {
        String className = getClass().getSimpleName();
        // Convert CamelCase to SCREAMING_SNAKE_CASE
        return className.replaceAll("([A-Z])", "_$1").toUpperCase().substring(1);
    }
}
