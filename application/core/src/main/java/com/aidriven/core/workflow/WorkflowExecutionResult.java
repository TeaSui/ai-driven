package com.aidriven.core.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of executing a complete workflow.
 *
 * @param workflowId      The workflow that was executed
 * @param ticketKey       The ticket this execution was for
 * @param success         Whether all steps completed successfully
 * @param completedSteps  IDs of steps that completed (including skipped)
 * @param failedStep      ID of the step that failed (null if success)
 * @param errorMessage    Error description if the workflow failed
 * @param outputs         Final context outputs after all steps
 * @param startedAt       When execution began
 * @param completedAt     When execution ended
 */
public record WorkflowExecutionResult(
        String workflowId,
        String ticketKey,
        boolean success,
        List<String> completedSteps,
        String failedStep,
        String errorMessage,
        Map<String, Object> outputs,
        Instant startedAt,
        Instant completedAt) {

    public Duration duration() {
        if (startedAt == null || completedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, completedAt);
    }

    public boolean isFailure() {
        return !success;
    }
}
