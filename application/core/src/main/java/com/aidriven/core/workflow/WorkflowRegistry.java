package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for {@link WorkflowDefinition}s.
 *
 * <p>Separates workflow registration concerns from execution concerns.
 * The {@link WorkflowEngine} delegates to this registry for lookups.
 *
 * <p>Supports dynamic registration at runtime (e.g., from configuration).
 */
@Slf4j
public class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();

    /**
     * Registers a workflow definition.
     *
     * @throws IllegalArgumentException if a workflow with the same ID is already registered
     *                                  and {@code allowOverwrite} is false
     */
    public void register(WorkflowDefinition definition) {
        register(definition, false);
    }

    /**
     * Registers a workflow definition, optionally allowing overwrite.
     */
    public void register(WorkflowDefinition definition, boolean allowOverwrite) {
        String id = definition.getWorkflowId();
        if (!allowOverwrite && definitions.containsKey(id)) {
            throw new IllegalArgumentException("Workflow '" + id + "' is already registered");
        }
        definitions.put(id, definition);
        log.info("Registered workflow '{}' ({} steps)", id, definition.getStepOrder().size());
    }

    /**
     * Looks up a workflow definition by ID.
     */
    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.ofNullable(definitions.get(workflowId));
    }

    /**
     * Returns all registered workflow definitions.
     */
    public Collection<WorkflowDefinition> all() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    /**
     * Returns all registered workflow IDs.
     */
    public java.util.Set<String> workflowIds() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    /**
     * Returns true if a workflow with the given ID is registered.
     */
    public boolean contains(String workflowId) {
        return definitions.containsKey(workflowId);
    }

    /**
     * Removes a workflow definition.
     */
    public void unregister(String workflowId) {
        definitions.remove(workflowId);
        log.info("Unregistered workflow '{}'", workflowId);
    }

    /**
     * Returns the number of registered workflows.
     */
    public int size() {
        return definitions.size();
    }
}
