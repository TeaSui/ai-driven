package com.aidriven.tool.context;

/**
 * Exception thrown when there is an error building context from repository.
 */
public class ContextBuildException extends RuntimeException {

    public ContextBuildException(String message) {
        super(message);
    }

    public ContextBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}

