package com.aidriven.core.workflow.steps;

import com.aidriven.core.workflow.WorkflowExecution;
import com.aidriven.core.workflow.WorkflowStep;
import com.aidriven.core.workflow.WorkflowStepException;
import com.aidriven.core.workflow.WorkflowStepResult;
import com.aidriven.spi.model.OperationContext;
import lombok.RequiredArgsConstructor;

/**
 * A no-op step that always succeeds.
 * Useful for testing, placeholders, and conditional branching endpoints.
 */
@RequiredArgsConstructor
public class NoOpStep implements WorkflowStep {

    private final String id;
    private final String message;

    public NoOpStep(String id) {
        this(id, "No-op step '" + id + "' completed");
    }

    @Override
    public String stepId() {
        return id;
    }

    @Override
    public WorkflowStepResult execute(OperationContext context, WorkflowExecution execution)
            throws WorkflowStepException {
        return WorkflowStepResult.success(message);
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
