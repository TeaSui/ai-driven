package com.aidriven.core.workflow;

/**
 * Thrown when a {@link WorkflowStep} fails in a non-recoverable way.
 */
public class WorkflowStepException extends Exception {

    private final String stepId;
    private final boolean retryable;

    public WorkflowStepException(String stepId, String message) {
        super(message);
        this.stepId = stepId;
        this.retryable = false;
    }

    public WorkflowStepException(String stepId, String message, Throwable cause) {
        super(message, cause);
        this.stepId = stepId;
        this.retryable = false;
    }

    public WorkflowStepException(String stepId, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.stepId = stepId;
        this.retryable = retryable;
    }

    public String getStepId() {
        return stepId;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
