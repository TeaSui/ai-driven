package com.aidriven.core.workflow;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of a workflow: an ordered sequence of steps.
 *
 * <p>Workflows are registered in the {@link WorkflowRegistry} and looked up
 * by their {@link #workflowId()}. The same definition can be executed
 * concurrently for different tickets.
 *
 * <p>Example:
 * <pre>{@code
 * WorkflowDefinition.builder("ai-generate")
 *     .displayName("AI Code Generation Pipeline")
 *     .step(fetchTicketStep)
 *     .step(fetchCodeStep)
 *     .step(generateCodeStep)
 *     .step(createPrStep)
 *     .build();
 * }</pre>
 */
public final class WorkflowDefinition {

    private final String workflowId;
    private final String displayName;
    private final List<WorkflowStep> steps;

    private WorkflowDefinition(Builder builder) {
        this.workflowId = Objects.requireNonNull(builder.workflowId, "workflowId");
        this.displayName = builder.displayName != null ? builder.displayName : workflowId;
        this.steps = Collections.unmodifiableList(List.copyOf(builder.steps));
    }

    public String workflowId() {
        return workflowId;
    }

    public String displayName() {
        return displayName;
    }

    public List<WorkflowStep> steps() {
        return steps;
    }

    public static Builder builder(String workflowId) {
        return new Builder(workflowId);
    }

    public static final class Builder {
        private final String workflowId;
        private String displayName;
        private final java.util.ArrayList<WorkflowStep> steps = new java.util.ArrayList<>();

        private Builder(String workflowId) {
            this.workflowId = workflowId;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder step(WorkflowStep step) {
            Objects.requireNonNull(step, "step must not be null");
            this.steps.add(step);
            return this;
        }

        public Builder steps(List<WorkflowStep> steps) {
            Objects.requireNonNull(steps, "steps must not be null");
            this.steps.addAll(steps);
            return this;
        }

        public WorkflowDefinition build() {
            if (steps.isEmpty()) {
                throw new IllegalStateException("WorkflowDefinition '" + workflowId + "' must have at least one step");
            }
            return new WorkflowDefinition(this);
        }
    }
}
