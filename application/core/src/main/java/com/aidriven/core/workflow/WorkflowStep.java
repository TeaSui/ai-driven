package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;

/**
 * A single executable step in a workflow.
 * Implementations define discrete units of work that can be composed into workflows.
 *
 * <p>Steps are stateless and reusable across multiple workflow definitions.
 * All state is passed via {@link WorkflowContext}.
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
     * Executes this step.
     *
     * @param context   Security and tenant context
     * @param wfContext Mutable workflow execution context (inputs/outputs)
     * @return Result of this step execution
     * @throws WorkflowStepException if the step fails in a non-recoverable way
     */
    WorkflowStepResult execute(OperationContext context, WorkflowContext wfContext) throws WorkflowStepException;

    /**
     * Whether this step can be skipped if its output already exists in the context.
     * Enables idempotent re-runs. Default: false.
     */
    default boolean isIdempotent() {
        return false;
    }

    /**
     * Whether a failure in this step should abort the entire workflow.
     * Default: true (fail-fast).
     */
    default boolean isRequired() {
        return true;
    }
}
