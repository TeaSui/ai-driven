package com.aidriven.core.workflow;

/**
 * Overall execution status of a workflow run.
 */
public enum WorkflowStatus {
    /** All mandatory steps completed successfully. */
    COMPLETED,
    /** One or more mandatory steps failed and the workflow was halted. */
    FAILED,
    /** Workflow was cancelled before completion. */
    CANCELLED,
    /** Workflow is currently executing (used for async/long-running workflows). */
    IN_PROGRESS
}
