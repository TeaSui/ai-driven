package com.aidriven.core.workflow;

/**
 * Execution status of a single {@link WorkflowStep}.
 */
public enum WorkflowStepStatus {
    /** Step completed successfully. */
    SUCCESS,
    /** Step was intentionally skipped (optional step, preconditions not met). */
    SKIPPED,
    /** Step failed; workflow may halt or continue depending on configuration. */
    FAILED
}
