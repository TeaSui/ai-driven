package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;

/**
 * A single executable step in a workflow.
 * Implementations encapsulate discrete units of work that can be composed
 * into larger workflows via {@link WorkflowDefinition}.
 *
 * <p>Steps are stateless and thread-safe. All state is passed via
 * {@link WorkflowContext}.
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
     * @param wfContext Mutable workflow execution context (input/output data)
     * @return Result of this step execution
     * @throws WorkflowStepException if the step fails in a non-retryable way
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
     * Maximum number of retry attempts for this step.
     * Default: 0 (no retries).
     */
    default int maxRetries() {
        return 0;
    }
}
