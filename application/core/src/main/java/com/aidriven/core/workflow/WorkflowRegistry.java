package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry for {@link WorkflowDefinition}s.
 * Allows dynamic registration and lookup of named workflows.
 *
 * <p>Typical usage:
 * <pre>{@code
 * WorkflowRegistry registry = new WorkflowRegistry();
 * registry.register(aiGeneratePipeline);
 * registry.register(agentWorkflow);
 *
 * WorkflowDefinition wf = registry.get("ai-generate").orElseThrow(...);
 * }</pre>
 */
@Slf4j
public class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> workflows = new LinkedHashMap<>();

    /**
     * Registers a workflow definition.
     * Overwrites any existing definition with the same ID.
     */
    public void register(WorkflowDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null WorkflowDefinition");
            return;
        }
        workflows.put(definition.getWorkflowId(), definition);
        log.info("Registered workflow '{}' ({}) with {} steps",
                definition.getWorkflowId(),
                definition.getDisplayName(),
                definition.getSteps().size());
    }

    /**
     * Retrieves a workflow by ID.
     */
    public Optional<WorkflowDefinition> get(String workflowId) {
        return Optional.ofNullable(workflows.get(workflowId));
    }

    /**
     * Returns all registered workflow IDs.
     */
    public Set<String> getRegisteredIds() {
        return Collections.unmodifiableSet(workflows.keySet());
    }

    /**
     * Returns all registered workflow definitions.
     */
    public Map<String, WorkflowDefinition> getAll() {
        return Collections.unmodifiableMap(workflows);
    }

    /**
     * Checks if a workflow is registered.
     */
    public boolean contains(String workflowId) {
        return workflows.containsKey(workflowId);
    }

    /**
     * Removes a workflow definition.
     */
    public void unregister(String workflowId) {
        if (workflows.remove(workflowId) != null) {
            log.info("Unregistered workflow '{}'", workflowId);
        }
    }
}
