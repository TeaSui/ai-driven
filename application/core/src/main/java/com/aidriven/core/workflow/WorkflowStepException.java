package com.aidriven.core.workflow;

/**
 * Thrown when a {@link WorkflowStep} encounters a non-recoverable error.
 * Wraps the underlying cause and carries the step ID for diagnostics.
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

    /** The ID of the step that failed. */
    public String getStepId() {
        return stepId;
    }

    /** Whether the failure is transient and the step may be retried. */
    public boolean isRetryable() {
        return retryable;
    }
}
