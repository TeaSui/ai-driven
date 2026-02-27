package com.aidriven.core.workflow;

import lombok.Getter;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defines the structure of a workflow: its steps, their order, and routing rules.
 *
 * <p>Use the {@link Builder} to construct definitions fluently:
 * <pre>{@code
 * WorkflowDefinition def = WorkflowDefinition.builder("code-generation")
 *     .description("Generates code from a Jira ticket")
 *     .step(fetchTicketStep)
 *     .step(fetchCodeStep)
 *     .step(generateCodeStep)
 *     .step(createPrStep)
 *     .build();
 * }</pre>
 */
@Getter
public class WorkflowDefinition {

    @NonNull
    private final String workflowId;

    private final String description;

    /** Ordered list of step IDs defining the default execution sequence. */
    @NonNull
    private final List<String> stepOrder;

    /** Map of stepId → WorkflowStep implementation. */
    @NonNull
    private final Map<String, WorkflowStep> steps;

    /** Optional: conditional routing rules (stepId → condition → nextStepId). */
    @NonNull
    private final Map<String, Map<String, String>> routingRules;

    /** Whether to continue on non-fatal step failures. Default: false. */
    private final boolean continueOnFailure;

    private WorkflowDefinition(Builder builder) {
        this.workflowId = builder.workflowId;
        this.description = builder.description;
        this.stepOrder = Collections.unmodifiableList(new ArrayList<>(builder.stepOrder));
        this.steps = Collections.unmodifiableMap(new LinkedHashMap<>(builder.steps));
        this.routingRules = Collections.unmodifiableMap(new LinkedHashMap<>(builder.routingRules));
        this.continueOnFailure = builder.continueOnFailure;
    }

    /**
     * Returns the step implementation for the given stepId.
     *
     * @throws IllegalArgumentException if the stepId is not registered
     */
    public WorkflowStep getStep(String stepId) {
        WorkflowStep step = steps.get(stepId);
        if (step == null) {
            throw new IllegalArgumentException(
                    "Step '" + stepId + "' not found in workflow '" + workflowId + "'");
        }
        return step;
    }

    /**
     * Returns the first step in the workflow.
     */
    public String firstStepId() {
        if (stepOrder.isEmpty()) {
            throw new IllegalStateException("Workflow '" + workflowId + "' has no steps defined");
        }
        return stepOrder.get(0);
    }

    /**
     * Returns the next step after the given stepId, or null if it's the last step.
     */
    public String nextStepId(String currentStepId) {
        int idx = stepOrder.indexOf(currentStepId);
        if (idx < 0 || idx >= stepOrder.size() - 1) {
            return null;
        }
        return stepOrder.get(idx + 1);
    }

    /**
     * Validates that all referenced steps are registered.
     *
     * @throws IllegalStateException if the definition is invalid
     */
    public void validate() {
        if (stepOrder.isEmpty()) {
            throw new IllegalStateException("Workflow '" + workflowId + "' must have at least one step");
        }
        for (String stepId : stepOrder) {
            if (!steps.containsKey(stepId)) {
                throw new IllegalStateException(
                        "Step '" + stepId + "' is in stepOrder but not registered in workflow '" + workflowId + "'");
            }
        }
        // Validate each step
        steps.values().forEach(WorkflowStep::validate);
    }

    public static Builder builder(String workflowId) {
        return new Builder(workflowId);
    }

    public static final class Builder {
        private final String workflowId;
        private String description;
        private final List<String> stepOrder = new ArrayList<>();
        private final Map<String, WorkflowStep> steps = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> routingRules = new LinkedHashMap<>();
        private boolean continueOnFailure = false;

        private Builder(String workflowId) {
            this.workflowId = Objects.requireNonNull(workflowId, "workflowId must not be null");
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Adds a step to the workflow in sequential order.
         */
        public Builder step(@NonNull WorkflowStep step) {
            String id = step.stepId();
            if (steps.containsKey(id)) {
                throw new IllegalArgumentException(
                        "Duplicate step id '" + id + "' in workflow '" + workflowId + "'");
            }
            steps.put(id, step);
            stepOrder.add(id);
            return this;
        }

        /**
         * Adds a conditional routing rule: after {@code fromStepId}, if {@code condition}
         * is met, route to {@code toStepId}.
         */
        public Builder route(String fromStepId, String condition, String toStepId) {
            routingRules.computeIfAbsent(fromStepId, k -> new LinkedHashMap<>())
                    .put(condition, toStepId);
            return this;
        }

        public Builder continueOnFailure(boolean continueOnFailure) {
            this.continueOnFailure = continueOnFailure;
            return this;
        }

        public WorkflowDefinition build() {
            WorkflowDefinition def = new WorkflowDefinition(this);
            def.validate();
            return def;
        }
    }
}
