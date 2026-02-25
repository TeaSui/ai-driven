package com.aidriven.core.workflow;

/**
 * Thrown when a workflow type is requested that has not been registered
 * in the {@link WorkflowRegistry}.
 */
public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String message) {
        super(message);
    }

    public WorkflowNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
