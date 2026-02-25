package com.aidriven.core.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of a workflow as an ordered sequence of steps.
 *
 * <p>Use the {@link Builder} to compose workflows from registered steps.
 *
 * <pre>{@code
 * WorkflowDefinition pipeline = WorkflowDefinition.builder("ai-generate")
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
    private final boolean stopOnFirstFailure;

    private WorkflowDefinition(Builder builder) {
        this.workflowId = Objects.requireNonNull(builder.workflowId, "workflowId");
        this.displayName = builder.displayName != null ? builder.displayName : workflowId;
        this.steps = Collections.unmodifiableList(new ArrayList<>(builder.steps));
        this.stopOnFirstFailure = builder.stopOnFirstFailure;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<WorkflowStep> getSteps() {
        return steps;
    }

    public boolean isStopOnFirstFailure() {
        return stopOnFirstFailure;
    }

    public static Builder builder(String workflowId) {
        return new Builder(workflowId);
    }

    public static final class Builder {
        private final String workflowId;
        private String displayName;
        private final List<WorkflowStep> steps = new ArrayList<>();
        private boolean stopOnFirstFailure = true;

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

        /**
         * If true (default), the workflow halts on the first non-optional step failure.
         * If false, all steps are attempted and failures are collected.
         */
        public Builder stopOnFirstFailure(boolean stopOnFirstFailure) {
            this.stopOnFirstFailure = stopOnFirstFailure;
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
