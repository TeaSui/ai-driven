package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of all available {@link WorkflowDefinition}s.
 *
 * <p>Workflows are registered at startup and looked up by ID at runtime.
 * This is the central extension point: adding a new workflow requires only
 * registering a new {@link WorkflowDefinition} — no changes to the engine.
 */
@Slf4j
public class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();

    /**
     * Registers a workflow definition.
     *
     * @param definition The workflow to register
     * @throws IllegalArgumentException if a workflow with the same ID is already registered
     */
    public void register(WorkflowDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null WorkflowDefinition");
            return;
        }
        if (definitions.containsKey(definition.workflowId())) {
            throw new IllegalArgumentException(
                    "Workflow '" + definition.workflowId() + "' is already registered");
        }
        definitions.put(definition.workflowId(), definition);
        log.info("Registered workflow: {} ({} steps)",
                definition.workflowId(), definition.steps().size());
    }

    /**
     * Looks up a workflow by ID.
     *
     * @param workflowId The workflow identifier
     * @return The workflow definition, or empty if not found
     */
    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.ofNullable(definitions.get(workflowId));
    }

    /**
     * Returns all registered workflow IDs.
     */
    public Set<String> registeredWorkflowIds() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    /**
     * Returns the number of registered workflows.
     */
    public int size() {
        return definitions.size();
    }
}
