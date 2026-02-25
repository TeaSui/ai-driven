package com.aidriven.core.workflow;

/**
 * Overall status of a workflow execution.
 */
public enum WorkflowExecutionStatus {
    /** Workflow is currently executing. */
    RUNNING,
    /** All steps completed successfully (or were skipped). */
    COMPLETED,
    /** One or more steps failed and the workflow was halted. */
    FAILED
}
