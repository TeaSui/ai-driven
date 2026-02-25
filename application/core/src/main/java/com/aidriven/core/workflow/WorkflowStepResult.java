package com.aidriven.core.workflow;

import java.util.Map;
import java.util.Objects;

/**
 * Result of executing a single {@link WorkflowStep}.
 *
 * @param stepId    The step that produced this result
 * @param status    Execution status
 * @param outputs   Key-value outputs to merge into {@link WorkflowContext}
 * @param message   Optional human-readable message (for logging/debugging)
 */
public record WorkflowStepResult(
        String stepId,
        StepStatus status,
        Map<String, Object> outputs,
        String message) {

    public enum StepStatus {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    /** Compact constructor with validation. */
    public WorkflowStepResult {
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        outputs = outputs != null ? Map.copyOf(outputs) : Map.of();
    }

    public static WorkflowStepResult success(String stepId, Map<String, Object> outputs) {
        return new WorkflowStepResult(stepId, StepStatus.SUCCESS, outputs, null);
    }

    public static WorkflowStepResult success(String stepId, Map<String, Object> outputs, String message) {
        return new WorkflowStepResult(stepId, StepStatus.SUCCESS, outputs, message);
    }

    public static WorkflowStepResult skipped(String stepId, String reason) {
        return new WorkflowStepResult(stepId, StepStatus.SKIPPED, Map.of(), reason);
    }

    public static WorkflowStepResult failed(String stepId, String errorMessage) {
        return new WorkflowStepResult(stepId, StepStatus.FAILED, Map.of(), errorMessage);
    }

    public boolean isSuccess() {
        return status == StepStatus.SUCCESS;
    }

    public boolean isSkipped() {
        return status == StepStatus.SKIPPED;
    }

    public boolean isFailed() {
        return status == StepStatus.FAILED;
    }
}
