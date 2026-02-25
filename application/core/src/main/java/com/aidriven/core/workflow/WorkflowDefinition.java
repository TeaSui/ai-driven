package com.aidriven.core.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of a workflow: an ordered sequence of steps.
 *
 * <p>Workflow definitions are registered in the {@link WorkflowRegistry} and
 * instantiated per execution. They are stateless — all mutable state lives
 * in {@link WorkflowState}.
 *
 * <p>Example usage:
 * <pre>{@code
 * WorkflowDefinition pipeline = WorkflowDefinition.builder()
 *     .id("ai-generate")
 *     .displayName("AI Code Generation Pipeline")
 *     .step(new FetchTicketStep(jiraClient))
 *     .step(new FetchCodeContextStep(sourceControlClient))
 *     .step(new GenerateCodeStep(aiClient))
 *     .step(new CreatePullRequestStep(sourceControlClient))
 *     .build();
 * }</pre>
 */
public final class WorkflowDefinition {

    private final String id;
    private final String displayName;
    private final List<WorkflowStep> steps;
    private final boolean haltOnStepFailure;

    private WorkflowDefinition(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.displayName = builder.displayName != null ? builder.displayName : builder.id;
        this.steps = List.copyOf(builder.steps);
        this.haltOnStepFailure = builder.haltOnStepFailure;
    }

    /** Unique identifier for this workflow (e.g., "ai-generate", "ai-agent"). */
    public String getId() {
        return id;
    }

    /** Human-readable name for logging and observability. */
    public String getDisplayName() {
        return displayName;
    }

    /** Ordered list of steps to execute. */
    public List<WorkflowStep> getSteps() {
        return steps;
    }

    /**
     * Whether the workflow should halt immediately when a step fails.
     * Defaults to true. Set to false to continue executing remaining steps
     * even after a failure (useful for cleanup/notification steps).
     */
    public boolean isHaltOnStepFailure() {
        return haltOnStepFailure;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String displayName;
        private final java.util.ArrayList<WorkflowStep> steps = new java.util.ArrayList<>();
        private boolean haltOnStepFailure = true;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
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

        public Builder haltOnStepFailure(boolean haltOnStepFailure) {
            this.haltOnStepFailure = haltOnStepFailure;
            return this;
        }

        public WorkflowDefinition build() {
            if (steps.isEmpty()) {
                throw new IllegalStateException("WorkflowDefinition must have at least one step");
            }
            return new WorkflowDefinition(this);
        }
    }
}
