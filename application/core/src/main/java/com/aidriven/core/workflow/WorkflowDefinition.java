package com.aidriven.core.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of a workflow: an ordered sequence of {@link WorkflowStep}s
 * with metadata and execution policy.
 *
 * <p>Workflow definitions are registered in the {@link WorkflowRegistry} and
 * instantiated per execution by the {@link WorkflowEngine}.
 */
public record WorkflowDefinition(
        String workflowType,
        String description,
        List<WorkflowStep> steps,
        WorkflowPolicy policy) {

    public WorkflowDefinition {
        Objects.requireNonNull(workflowType, "workflowType must not be null");
        Objects.requireNonNull(steps, "steps must not be null");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("WorkflowDefinition must have at least one step");
        }
        steps = List.copyOf(steps);
        policy = policy != null ? policy : WorkflowPolicy.defaults();
    }

    /** Convenience constructor with default policy. */
    public WorkflowDefinition(String workflowType, String description, List<WorkflowStep> steps) {
        this(workflowType, description, steps, WorkflowPolicy.defaults());
    }

    /** Returns the number of steps in this workflow. */
    public int stepCount() {
        return steps.size();
    }
}
