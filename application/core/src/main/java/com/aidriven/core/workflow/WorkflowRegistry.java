package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry for {@link WorkflowDefinition}s.
 * Workflows are registered by ID and looked up at execution time.
 *
 * <p>This follows the same pattern as {@link com.aidriven.core.agent.tool.ToolRegistry}:
 * a central registry that maps identifiers to implementations, enabling
 * modular composition without tight coupling.
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
        if (definitions.containsKey(definition.getId())) {
            throw new IllegalArgumentException(
                    "Workflow already registered with id: " + definition.getId());
        }
        definitions.put(definition.getId(), definition);
        log.info("Registered workflow: {} ({} steps)",
                definition.getId(), definition.getSteps().size());
    }

    /**
     * Looks up a workflow definition by ID.
     *
     * @param workflowId The workflow identifier
     * @return Optional containing the definition, or empty if not found
     */
    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.ofNullable(definitions.get(workflowId));
    }

    /**
     * Returns all registered workflow IDs.
     */
    public Set<String> getRegisteredIds() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    /**
     * Returns true if a workflow with the given ID is registered.
     */
    public boolean isRegistered(String workflowId) {
        return definitions.containsKey(workflowId);
    }
}
