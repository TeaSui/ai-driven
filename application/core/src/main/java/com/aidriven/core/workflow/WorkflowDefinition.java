package com.aidriven.core.workflow;

import java.util.List;

/**
 * Defines a named, ordered sequence of workflow steps.
 *
 * <p>Workflow definitions are immutable and reusable. They describe WHAT steps
 * to execute and in what order, but not HOW (that's the step implementations).
 *
 * <p>Example workflows:
 * <ul>
 *   <li>"ai-generate" — fetch ticket → fetch code → invoke AI → create PR → wait for merge</li>
 *   <li>"ai-agent" — classify intent → run ReAct loop → post response</li>
 * </ul>
 */
public interface WorkflowDefinition {

    /**
     * Unique identifier for this workflow (e.g., "ai-generate", "ai-agent").
     */
    String workflowId();

    /**
     * Human-readable name.
     */
    String name();

    /**
     * Human-readable description.
     */
    String description();

    /**
     * Ordered list of step IDs to execute.
     * Steps are resolved from the {@link WorkflowStepRegistry} at runtime.
     */
    List<String> stepIds();

    /**
     * Whether this workflow supports partial re-execution (resume from last successful step).
     */
    default boolean supportsResume() {
        return false;
    }

    /**
     * Maximum total execution time in seconds before the workflow is aborted.
     * Default: 900 seconds (15 minutes).
     */
    default int timeoutSeconds() {
        return 900;
    }
}
