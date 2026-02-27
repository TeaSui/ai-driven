package com.aidriven.core.workflow;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.util.Collections;
import java.util.Map;

/**
 * The result of executing a {@link WorkflowStep}.
 * Carries the outcome, output data, and optional next-step routing hint.
 */
@Getter
@Builder
public class WorkflowStepResult {

    public enum Status {
        /** Step completed successfully. */
        SUCCESS,
        /** Step failed but can be retried. */
        RETRYABLE_FAILURE,
        /** Step failed permanently — workflow should halt. */
        FATAL_FAILURE,
        /** Step is waiting for an external event (e.g., approval). */
        WAITING
    }

    @NonNull
    private final Status status;

    /** Human-readable message describing the outcome. */
    private final String message;

    /**
     * Output data produced by this step.
     * Available to subsequent steps via {@link WorkflowExecution#getStepOutput}.
     */
    @Builder.Default
    private final Map<String, Object> output = Collections.emptyMap();

    /**
     * Optional: override the next step to execute.
     * If null, the workflow engine uses the default sequential order.
     */
    private final String nextStepId;

    // ─── Factory methods ─────────────────────────────────────────────────────

    public static WorkflowStepResult success() {
        return WorkflowStepResult.builder().status(Status.SUCCESS).build();
    }

    public static WorkflowStepResult success(String message) {
        return WorkflowStepResult.builder().status(Status.SUCCESS).message(message).build();
    }

    public static WorkflowStepResult success(Map<String, Object> output) {
        return WorkflowStepResult.builder().status(Status.SUCCESS).output(output).build();
    }

    public static WorkflowStepResult success(String message, Map<String, Object> output) {
        return WorkflowStepResult.builder().status(Status.SUCCESS).message(message).output(output).build();
    }

    public static WorkflowStepResult retryableFailure(String message) {
        return WorkflowStepResult.builder().status(Status.RETRYABLE_FAILURE).message(message).build();
    }

    public static WorkflowStepResult fatalFailure(String message) {
        return WorkflowStepResult.builder().status(Status.FATAL_FAILURE).message(message).build();
    }

    public static WorkflowStepResult waiting(String message) {
        return WorkflowStepResult.builder().status(Status.WAITING).message(message).build();
    }

    public static WorkflowStepResult routeTo(String nextStepId) {
        return WorkflowStepResult.builder().status(Status.SUCCESS).nextStepId(nextStepId).build();
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isFailure() {
        return status == Status.RETRYABLE_FAILURE || status == Status.FATAL_FAILURE;
    }

    public boolean isWaiting() {
        return status == Status.WAITING;
    }
}
