package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of available {@link WorkflowDefinition}s.
 *
 * <p>Workflow definitions are registered at startup and looked up by
 * {@code workflowType} string (e.g., "ai-generate", "ai-agent").
 *
 * <p>This is the central extension point for adding new workflow types
 * without modifying the {@link WorkflowEngine}.
 */
@Slf4j
public class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();

    /**
     * Registers a workflow definition.
     *
     * @param definition The workflow definition to register
     * @throws IllegalArgumentException if a definition with the same type is already registered
     */
    public void register(WorkflowDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null WorkflowDefinition");
            return;
        }
        if (definitions.containsKey(definition.workflowType())) {
            throw new IllegalArgumentException(
                    "WorkflowDefinition already registered for type: " + definition.workflowType());
        }
        definitions.put(definition.workflowType(), definition);
        log.info("Registered workflow: {} ({} steps)",
                definition.workflowType(), definition.stepCount());
    }

    /**
     * Registers or replaces a workflow definition.
     * Use this when overriding defaults in tests or dynamic reconfiguration.
     */
    public void registerOrReplace(WorkflowDefinition definition) {
        if (definition == null) return;
        definitions.put(definition.workflowType(), definition);
        log.info("Registered/replaced workflow: {} ({} steps)",
                definition.workflowType(), definition.stepCount());
    }

    /**
     * Looks up a workflow definition by type.
     *
     * @param workflowType The workflow type identifier
     * @return The definition, or empty if not registered
     */
    public Optional<WorkflowDefinition> find(String workflowType) {
        return Optional.ofNullable(definitions.get(workflowType));
    }

    /**
     * Returns all registered workflow type identifiers.
     */
    public Set<String> registeredTypes() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    /**
     * Returns the number of registered workflow definitions.
     */
    public int size() {
        return definitions.size();
    }
}
