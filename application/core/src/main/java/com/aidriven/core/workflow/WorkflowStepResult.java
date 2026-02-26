package com.aidriven.core.workflow;

import java.util.Map;

/**
 * Result of executing a single workflow step.
 *
 * @param success      Whether the step completed successfully
 * @param outputs      Key-value outputs produced by the step (merged into WorkflowContext)
 * @param errorMessage Error description if the step failed
 * @param skipped      Whether the step was skipped (idempotency)
 */
public record WorkflowStepResult(
        boolean success,
        Map<String, Object> outputs,
        String errorMessage,
        boolean skipped) {

    public static WorkflowStepResult success(Map<String, Object> outputs) {
        return new WorkflowStepResult(true, outputs != null ? outputs : Map.of(), null, false);
    }

    public static WorkflowStepResult success() {
        return success(Map.of());
    }

    public static WorkflowStepResult failure(String errorMessage) {
        return new WorkflowStepResult(false, Map.of(), errorMessage, false);
    }

    public static WorkflowStepResult skipped() {
        return new WorkflowStepResult(true, Map.of(), null, true);
    }

    public boolean isFailure() {
        return !success;
    }
}
