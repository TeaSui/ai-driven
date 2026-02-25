package com.aidriven.core.workflow;

/**
 * Execution status of a single workflow step.
 */
public enum WorkflowStepStatus {
    /** Step completed successfully. */
    SUCCESS,
    /** Step was intentionally skipped (e.g., dry-run mode, condition not met). */
    SKIPPED,
    /** Step failed. Workflow may halt or continue depending on configuration. */
    FAILED
}
