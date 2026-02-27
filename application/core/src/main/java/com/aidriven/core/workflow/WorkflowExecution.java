package com.aidriven.core.workflow;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the runtime state of a workflow execution.
 * Carries input data, step outputs, and execution metadata.
 *
 * <p>Thread-safe for concurrent step output writes.
 */
@Getter
public class WorkflowExecution {

    public enum ExecutionStatus {
        PENDING, RUNNING, COMPLETED, FAILED, WAITING
    }

    @NonNull
    private final String executionId;

    @NonNull
    private final String workflowId;

    @NonNull
    private final String tenantId;

    /** Input data passed to the workflow at start time. */
    @NonNull
    private final Map<String, Object> input;

    /** Accumulated outputs from all completed steps, keyed by stepId. */
    private final Map<String, Map<String, Object>> stepOutputs = new ConcurrentHashMap<>();

    /** Shared context variables that steps can read and write. */
    private final Map<String, Object> variables = new ConcurrentHashMap<>();

    private volatile ExecutionStatus status;
    private volatile String currentStepId;
    private volatile String errorMessage;
    private final Instant startedAt;
    private volatile Instant completedAt;

    @Builder
    public WorkflowExecution(
            @NonNull String executionId,
            @NonNull String workflowId,
            @NonNull String tenantId,
            Map<String, Object> input) {
        this.executionId = executionId;
        this.workflowId = workflowId;
        this.tenantId = tenantId;
        this.input = input != null ? Collections.unmodifiableMap(new HashMap<>(input)) : Collections.emptyMap();
        this.status = ExecutionStatus.PENDING;
        this.startedAt = Instant.now();
    }

    /**
     * Records the output of a completed step.
     */
    public void recordStepOutput(String stepId, Map<String, Object> output) {
        if (output != null && !output.isEmpty()) {
            stepOutputs.put(stepId, Collections.unmodifiableMap(new HashMap<>(output)));
        }
    }

    /**
     * Retrieves the output of a previously completed step.
     */
    public Optional<Map<String, Object>> getStepOutput(String stepId) {
        return Optional.ofNullable(stepOutputs.get(stepId));
    }

    /**
     * Gets a typed value from a step's output.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getStepOutputValue(String stepId, String key) {
        return getStepOutput(stepId)
                .map(output -> (T) output.get(key));
    }

    /**
     * Gets a typed value from the input map.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getInput(String key) {
        return Optional.ofNullable((T) input.get(key));
    }

    /**
     * Sets a shared variable accessible to all subsequent steps.
     */
    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    /**
     * Gets a shared variable.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getVariable(String key) {
        return Optional.ofNullable((T) variables.get(key));
    }

    // ─── Status transitions ───────────────────────────────────────────────────

    public void markRunning(String stepId) {
        this.status = ExecutionStatus.RUNNING;
        this.currentStepId = stepId;
    }

    public void markCompleted() {
        this.status = ExecutionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = ExecutionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    public void markWaiting(String stepId) {
        this.status = ExecutionStatus.WAITING;
        this.currentStepId = stepId;
    }

    public boolean isTerminal() {
        return status == ExecutionStatus.COMPLETED || status == ExecutionStatus.FAILED;
    }
}
