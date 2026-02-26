package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for all available {@link WorkflowDefinition}s.
 * Definitions are registered at startup and resolved by workflow ID at runtime.
 */
@Slf4j
public class WorkflowDefinitionRegistry {

    private final Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();

    /**
     * Registers a workflow definition.
     */
    public void register(WorkflowDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null WorkflowDefinition");
            return;
        }
        definitions.put(definition.workflowId(), definition);
        log.info("Registered workflow definition: {} ({} steps)",
                definition.workflowId(), definition.stepIds().size());
    }

    /**
     * Resolves a workflow definition by ID.
     */
    public Optional<WorkflowDefinition> resolve(String workflowId) {
        return Optional.ofNullable(definitions.get(workflowId));
    }

    /**
     * Returns all registered workflow definitions.
     */
    public Collection<WorkflowDefinition> getAll() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    /**
     * Returns the number of registered definitions.
     */
    public int size() {
        return definitions.size();
    }
}
