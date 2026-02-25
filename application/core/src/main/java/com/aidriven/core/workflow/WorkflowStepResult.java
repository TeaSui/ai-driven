package com.aidriven.core.workflow;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable result from executing a {@link WorkflowStep}.
 * Carries output data that subsequent steps can consume via {@link WorkflowContext}.
 */
public record WorkflowStepResult(
        String stepId,
        WorkflowStepStatus status,
        String message,
        Map<String, Object> outputs) {

    public WorkflowStepResult {
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        outputs = outputs != null ? Map.copyOf(outputs) : Map.of();
    }

    /** Creates a successful result with output data. */
    public static WorkflowStepResult success(String stepId, Map<String, Object> outputs) {
        return new WorkflowStepResult(stepId, WorkflowStepStatus.SUCCESS, null, outputs);
    }

    /** Creates a successful result with a message and output data. */
    public static WorkflowStepResult success(String stepId, String message, Map<String, Object> outputs) {
        return new WorkflowStepResult(stepId, WorkflowStepStatus.SUCCESS, message, outputs);
    }

    /** Creates a skipped result (optional step whose preconditions were not met). */
    public static WorkflowStepResult skipped(String stepId, String reason) {
        return new WorkflowStepResult(stepId, WorkflowStepStatus.SKIPPED, reason, Map.of());
    }

    /** Creates a failed result. */
    public static WorkflowStepResult failed(String stepId, String errorMessage) {
        return new WorkflowStepResult(stepId, WorkflowStepStatus.FAILED, errorMessage, Map.of());
    }

    public boolean isSuccess() {
        return status == WorkflowStepStatus.SUCCESS;
    }

    public boolean isSkipped() {
        return status == WorkflowStepStatus.SKIPPED;
    }

    public boolean isFailed() {
        return status == WorkflowStepStatus.FAILED;
    }
}
