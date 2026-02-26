package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;

/**
 * A single executable step in a workflow.
 * Implementations define discrete units of work that can be composed into workflows.
 *
 * <p>Steps are stateless and reusable across workflow definitions.
 * All state is passed via {@link WorkflowContext}.
 */
public interface WorkflowStep {

    /**
     * Unique identifier for this step type (e.g., "fetch_ticket", "generate_code").
     */
    String stepId();

    /**
     * Human-readable description of what this step does.
     */
    String description();

    /**
     * Executes the step.
     *
     * @param context   Security and tenant context
     * @param wfContext Mutable workflow execution context (inputs/outputs)
     * @return Result of the step execution
     */
    WorkflowStepResult execute(OperationContext context, WorkflowContext wfContext);

    /**
     * Whether this step can be skipped if its output already exists in the context.
     * Enables idempotent re-runs.
     */
    default boolean isIdempotent() {
        return false;
    }

    /**
     * Maximum execution time in seconds before the step is considered timed out.
     * Default: 300 seconds (5 minutes).
     */
    default int timeoutSeconds() {
        return 300;
    }
}
