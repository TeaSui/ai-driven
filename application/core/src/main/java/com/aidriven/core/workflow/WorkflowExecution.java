package com.aidriven.core.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single execution of a {@link WorkflowDefinition}.
 * Tracks the results of each step and the overall execution status.
 */
public final class WorkflowExecution {

    private final String executionId;
    private final String workflowId;
    private final String ticketKey;
    private final Instant startedAt;
    private Instant completedAt;
    private WorkflowExecutionStatus status;
    private final List<WorkflowStepResult> stepResults;
    private String errorMessage;

    public WorkflowExecution(String executionId, String workflowId, String ticketKey) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
        this.ticketKey = Objects.requireNonNull(ticketKey, "ticketKey");
        this.startedAt = Instant.now();
        this.status = WorkflowExecutionStatus.RUNNING;
        this.stepResults = new ArrayList<>();
    }

    public String getExecutionId() { return executionId; }
    public String getWorkflowId() { return workflowId; }
    public String getTicketKey() { return ticketKey; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public WorkflowExecutionStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }

    /** Returns an unmodifiable view of step results in execution order. */
    public List<WorkflowStepResult> getStepResults() {
        return Collections.unmodifiableList(stepResults);
    }

    /** Records the result of a completed step. */
    void addStepResult(WorkflowStepResult result) {
        stepResults.add(result);
    }

    /** Marks the execution as successfully completed. */
    void complete() {
        this.status = WorkflowExecutionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** Marks the execution as failed. */
    void fail(String errorMessage) {
        this.status = WorkflowExecutionStatus.FAILED;
        this.completedAt = Instant.now();
        this.errorMessage = errorMessage;
    }

    /** Returns the duration of this execution in milliseconds, or -1 if still running. */
    public long getDurationMs() {
        if (completedAt == null) return -1;
        return completedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    /** Returns true if all recorded step results are successful or skipped. */
    public boolean allStepsSucceeded() {
        return stepResults.stream().noneMatch(WorkflowStepResult::isFailed);
    }
}
