package com.aidriven.core.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of executing a complete {@link WorkflowDefinition}.
 */
public record WorkflowResult(
        String workflowId,
        String ticketKey,
        WorkflowStatus status,
        List<WorkflowStepResult> stepResults,
        Map<String, Object> finalOutputs,
        Instant startedAt,
        Instant completedAt,
        String errorMessage) {

    public enum WorkflowStatus {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED
    }

    /** Compact constructor with validation and defensive copies. */
    public WorkflowResult {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(status, "status");
        stepResults = stepResults != null ? Collections.unmodifiableList(stepResults) : List.of();
        finalOutputs = finalOutputs != null ? Collections.unmodifiableMap(finalOutputs) : Map.of();
    }

    public boolean isSuccess() {
        return status == WorkflowStatus.SUCCESS;
    }

    public boolean isFailed() {
        return status == WorkflowStatus.FAILED;
    }

    /**
     * Total wall-clock duration of the workflow execution.
     */
    public Duration duration() {
        if (startedAt == null || completedAt == null) return Duration.ZERO;
        return Duration.between(startedAt, completedAt);
    }

    /**
     * Number of steps that completed successfully.
     */
    public long successfulStepCount() {
        return stepResults.stream().filter(WorkflowStepResult::isSuccess).count();
    }

    /**
     * Number of steps that failed.
     */
    public long failedStepCount() {
        return stepResults.stream().filter(WorkflowStepResult::isFailed).count();
    }
}
