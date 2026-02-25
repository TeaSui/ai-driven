package com.aidriven.core.workflow;

/**
 * Thrown when a {@link WorkflowStep} encounters a non-retryable failure.
 * Callers should catch this to halt the workflow and record the error.
 */
public class WorkflowStepException extends Exception {

    private final String stepId;

    public WorkflowStepException(String stepId, String message) {
        super(message);
        this.stepId = stepId;
    }

    public WorkflowStepException(String stepId, String message, Throwable cause) {
        super(message, cause);
        this.stepId = stepId;
    }

    /** The ID of the step that failed. */
    public String getStepId() {
        return stepId;
    }
}
