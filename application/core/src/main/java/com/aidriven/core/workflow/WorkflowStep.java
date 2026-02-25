package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;

/**
 * A single executable step in a workflow.
 * Implementations encapsulate discrete units of work (e.g., fetch ticket,
 * generate code, create PR) that can be composed into larger workflows.
 *
 * <p>Steps are stateless and reusable across workflow definitions.
 */
public interface WorkflowStep {

    /**
     * Unique identifier for this step type (e.g., "fetch_ticket", "generate_code").
     */
    String stepId();

    /**
     * Human-readable name for logging and observability.
     */
    String displayName();

    /**
     * Executes this step with the given context.
     *
     * @param context  The operation context (tenant, user, correlation IDs)
     * @param state    Mutable workflow state passed between steps
     * @return The result of this step execution
     * @throws WorkflowStepException if the step fails in a non-retryable way
     */
    WorkflowStepResult execute(OperationContext context, WorkflowState state) throws WorkflowStepException;

    /**
     * Whether this step can be retried on transient failure.
     * Defaults to true for most steps.
     */
    default boolean isRetryable() {
        return true;
    }

    /**
     * Maximum number of retry attempts for this step.
     * Only relevant when {@link #isRetryable()} returns true.
     */
    default int maxRetries() {
        return 3;
    }
}
