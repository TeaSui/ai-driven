package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry for all available {@link WorkflowStep} implementations.
 * Steps are registered by their {@link WorkflowStep#stepId()} and resolved at runtime.
 *
 * <p>This follows the same pattern as {@link com.aidriven.core.agent.tool.ToolRegistry}:
 * register once at startup, resolve by name at execution time.
 */
@Slf4j
public class WorkflowStepRegistry {

    private final Map<String, WorkflowStep> steps = new LinkedHashMap<>();

    /**
     * Registers a workflow step.
     *
     * @param step The step to register
     * @throws IllegalArgumentException if a step with the same ID is already registered
     */
    public void register(WorkflowStep step) {
        if (step == null) {
            log.warn("Attempted to register null WorkflowStep");
            return;
        }
        String id = step.stepId();
        if (steps.containsKey(id)) {
            log.warn("Overwriting existing workflow step: {}", id);
        }
        steps.put(id, step);
        log.info("Registered workflow step: {} - {}", id, step.description());
    }

    /**
     * Resolves a step by its ID.
     *
     * @param stepId The step identifier
     * @return The step, or empty if not found
     */
    public Optional<WorkflowStep> resolve(String stepId) {
        return Optional.ofNullable(steps.get(stepId));
    }

    /**
     * Returns all registered step IDs.
     */
    public Set<String> getRegisteredStepIds() {
        return Collections.unmodifiableSet(steps.keySet());
    }

    /**
     * Returns the number of registered steps.
     */
    public int size() {
        return steps.size();
    }
}
