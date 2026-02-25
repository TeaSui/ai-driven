package com.aidriven.core.workflow;

/**
 * Execution policy for a {@link WorkflowDefinition}.
 * Controls failure handling, timeouts, and retry behaviour.
 */
public record WorkflowPolicy(
        boolean haltOnStepFailure,
        int maxRetryAttempts,
        long stepTimeoutSeconds,
        boolean continueOnOptionalFailure) {

    /** Default policy: halt on failure, no retries, 5-minute step timeout. */
    public static WorkflowPolicy defaults() {
        return new WorkflowPolicy(true, 0, 300L, true);
    }

    /** Lenient policy: continue on failure, 3 retries, 10-minute step timeout. */
    public static WorkflowPolicy lenient() {
        return new WorkflowPolicy(false, 3, 600L, true);
    }

    /** Strict policy: halt on any failure, no retries, 2-minute step timeout. */
    public static WorkflowPolicy strict() {
        return new WorkflowPolicy(true, 0, 120L, false);
    }
}
