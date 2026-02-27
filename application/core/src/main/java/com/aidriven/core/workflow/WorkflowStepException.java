package com.aidriven.core.workflow;

/**
 * Thrown when a {@link WorkflowStep} fails during execution.
 */
public class WorkflowStepException extends Exception {

    private final String stepId;
    private final boolean retryable;

    public WorkflowStepException(String stepId, String message, boolean retryable) {
        super(message);
        this.stepId = stepId;
        this.retryable = retryable;
    }

    public WorkflowStepException(String stepId, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.stepId = stepId;
        this.retryable = retryable;
    }

    public static WorkflowStepException retryable(String stepId, String message) {
        return new WorkflowStepException(stepId, message, true);
    }

    public static WorkflowStepException retryable(String stepId, String message, Throwable cause) {
        return new WorkflowStepException(stepId, message, true, cause);
    }

    public static WorkflowStepException fatal(String stepId, String message) {
        return new WorkflowStepException(stepId, message, false);
    }

    public static WorkflowStepException fatal(String stepId, String message, Throwable cause) {
        return new WorkflowStepException(stepId, message, false, cause);
    }

    public String getStepId() {
        return stepId;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
