package com.aidriven.core.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a complete workflow execution.
 *
 * @param workflowId  The workflow that was executed
 * @param ticketKey   The ticket this execution was for
 * @param status      Overall execution status
 * @param stepResults Results of each step in order
 * @param outputs     Final merged outputs from all steps
 * @param startedAt   When execution began
 * @param completedAt When execution finished
 * @param errorMessage Error message if status is FAILED
 */
public record WorkflowResult(
        String workflowId,
        String ticketKey,
        WorkflowStatus status,
        List<WorkflowStepResult> stepResults,
        Map<String, Object> outputs,
        Instant startedAt,
        Instant completedAt,
        String errorMessage) {

    public enum WorkflowStatus {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED
    }

    public Duration duration() {
        if (startedAt == null || completedAt == null) return Duration.ZERO;
        return Duration.between(startedAt, completedAt);
    }

    public boolean isSuccess() {
        return status == WorkflowStatus.SUCCESS;
    }

    public boolean isFailed() {
        return status == WorkflowStatus.FAILED;
    }

    /**
     * Convenience factory for a successful result.
     */
    public static WorkflowResult success(String workflowId, String ticketKey,
            List<WorkflowStepResult> stepResults, Map<String, Object> outputs,
            Instant startedAt, Instant completedAt) {
        return new WorkflowResult(workflowId, ticketKey, WorkflowStatus.SUCCESS,
                Collections.unmodifiableList(stepResults),
                Collections.unmodifiableMap(outputs),
                startedAt, completedAt, null);
    }

    /**
     * Convenience factory for a failed result.
     */
    public static WorkflowResult failed(String workflowId, String ticketKey,
            List<WorkflowStepResult> stepResults, Map<String, Object> outputs,
            Instant startedAt, Instant completedAt, String errorMessage) {
        return new WorkflowResult(workflowId, ticketKey, WorkflowStatus.FAILED,
                Collections.unmodifiableList(stepResults),
                Collections.unmodifiableMap(outputs),
                startedAt, completedAt, errorMessage);
    }
}
