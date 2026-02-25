package com.aidriven.core.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of a complete workflow execution.
 * Aggregates results from all executed steps.
 */
public record WorkflowResult(
        String workflowId,
        String workflowType,
        WorkflowStatus status,
        List<WorkflowStepResult> stepResults,
        Map<String, Object> outputs,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {

    public WorkflowResult {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(workflowType, "workflowType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        stepResults = stepResults != null ? List.copyOf(stepResults) : List.of();
        outputs = outputs != null ? Map.copyOf(outputs) : Map.of();
    }

    /** Returns true if the workflow completed successfully. */
    public boolean isSuccess() {
        return status == WorkflowStatus.COMPLETED;
    }

    /** Returns true if the workflow failed. */
    public boolean isFailed() {
        return status == WorkflowStatus.FAILED;
    }

    /** Returns the total wall-clock duration of the workflow execution. */
    public Duration duration() {
        if (startedAt == null || completedAt == null) return Duration.ZERO;
        return Duration.between(startedAt, completedAt);
    }

    /** Returns the number of successfully completed steps. */
    public long successfulSteps() {
        return stepResults.stream().filter(WorkflowStepResult::isSuccess).count();
    }

    /** Returns the number of failed steps. */
    public long failedSteps() {
        return stepResults.stream().filter(WorkflowStepResult::isFailed).count();
    }
}
