package com.aidriven.core.workflow;

import java.util.Collections;
import java.util.Map;

/**
 * Result of executing a single {@link WorkflowStep}.
 *
 * @param stepId    The step that produced this result
 * @param status    Execution status
 * @param outputs   Key-value outputs to merge into the workflow context
 * @param message   Human-readable summary (for logging/audit)
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

    public static WorkflowStepResult success(String stepId, String message) {
        return new WorkflowStepResult(stepId, StepStatus.SUCCESS, Collections.emptyMap(), message);
    }

    public static WorkflowStepResult success(String stepId, Map<String, Object> outputs, String message) {
        return new WorkflowStepResult(stepId, StepStatus.SUCCESS,
                outputs != null ? Collections.unmodifiableMap(outputs) : Collections.emptyMap(), message);
    }

    public static WorkflowStepResult skipped(String stepId, String reason) {
        return new WorkflowStepResult(stepId, StepStatus.SKIPPED, Collections.emptyMap(), reason);
    }

    public static WorkflowStepResult failed(String stepId, String errorMessage) {
        return new WorkflowStepResult(stepId, StepStatus.FAILED, Collections.emptyMap(), errorMessage);
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
