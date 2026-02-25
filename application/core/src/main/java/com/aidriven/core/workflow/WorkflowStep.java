package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;

/**
 * A single executable step in a workflow.
 * Implementations encapsulate discrete units of work (e.g., fetch ticket,
 * generate code, create PR) that can be composed into a {@link WorkflowDefinition}.
 *
 * <p>Steps are stateless and reusable across multiple workflow definitions.
 * All state is passed via {@link WorkflowContext}.
 */
public interface WorkflowStep {

    /**
     * Unique identifier for this step type (e.g., "fetch_ticket", "generate_code").
     * Used for logging, metrics, and dependency resolution.
     */
    String stepId();

    /**
     * Human-readable description of what this step does.
     */
    String description();

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
     * Whether this step can be skipped if its preconditions are not met.
     * Default: false (step is mandatory).
     */
    default boolean isOptional() {
        return false;
    }

    /**
     * Maximum number of retry attempts for this step on transient failure.
     * Default: 0 (no retries).
     */
    default int maxRetries() {
        return 0;
    }
}
