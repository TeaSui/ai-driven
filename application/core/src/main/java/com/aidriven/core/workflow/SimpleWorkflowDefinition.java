package com.aidriven.core.workflow;

import java.util.List;

/**
 * Simple, immutable implementation of {@link WorkflowDefinition}.
 * Suitable for most use cases where a workflow is a fixed ordered list of steps.
 */
public record SimpleWorkflowDefinition(
        String workflowId,
        String name,
        String description,
        List<String> stepIds,
        boolean supportsResume,
        int timeoutSeconds) implements WorkflowDefinition {

    /**
     * Creates a simple workflow definition with default timeout (900s).
     */
    public static SimpleWorkflowDefinition of(
            String workflowId, String name, String description, List<String> stepIds) {
        return new SimpleWorkflowDefinition(workflowId, name, description, stepIds, false, 900);
    }

    /**
     * Creates a simple workflow definition with resume support.
     */
    public static SimpleWorkflowDefinition resumable(
            String workflowId, String name, String description, List<String> stepIds) {
        return new SimpleWorkflowDefinition(workflowId, name, description, stepIds, true, 900);
    }
}
