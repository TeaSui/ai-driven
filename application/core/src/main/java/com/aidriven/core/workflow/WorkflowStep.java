package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;

import java.util.Map;

/**
 * A single executable step in a workflow.
 * Steps are composable, reusable units of work that can be chained together.
 *
 * <p>Implementations should be stateless and thread-safe.
 */
public interface WorkflowStep {

    /**
     * Returns the unique identifier for this step type.
     * Used for registration, logging, and metrics.
     */
    String stepId();

    /**
     * Executes this step with the given context and input.
     *
     * @param context   The operation context (tenant, user, correlation IDs)
     * @param execution The current workflow execution state
     * @return The result of this step
     * @throws WorkflowStepException if the step fails
     */
    WorkflowStepResult execute(OperationContext context, WorkflowExecution execution) throws WorkflowStepException;

    /**
     * Returns true if this step can be retried on failure.
     * Default: true (most steps are idempotent).
     */
    default boolean isRetryable() {
        return true;
    }

    /**
     * Returns the maximum number of retry attempts for this step.
     * Default: 3.
     */
    default int maxRetries() {
        return 3;
    }

    /**
     * Optional: validate the step configuration before execution.
     * Called once at workflow registration time.
     *
     * @throws IllegalStateException if the step is misconfigured
     */
    default void validate() {
        // no-op by default
    }
}
