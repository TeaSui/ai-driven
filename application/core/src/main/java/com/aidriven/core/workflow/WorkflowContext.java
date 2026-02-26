package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable execution context passed through all steps of a workflow run.
 *
 * <p>Acts as a typed property bag: steps write their outputs here and
 * subsequent steps read them. Keys are namespaced by convention:
 * {@code stepId.outputKey} (e.g., {@code fetch_ticket.ticketKey}).
 *
 * <p>Thread-safety: NOT thread-safe. Each workflow execution uses its own instance.
 */
@Slf4j
public class WorkflowContext {

    private final String workflowId;
    private final String ticketKey;
    private final Map<String, Object> properties;

    public WorkflowContext(String workflowId, String ticketKey) {
        this.workflowId = workflowId;
        this.ticketKey = ticketKey;
        this.properties = new HashMap<>();
    }

    public WorkflowContext(String workflowId, String ticketKey, Map<String, Object> initialProperties) {
        this.workflowId = workflowId;
        this.ticketKey = ticketKey;
        this.properties = new HashMap<>(initialProperties != null ? initialProperties : Collections.emptyMap());
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTicketKey() {
        return ticketKey;
    }

    /**
     * Sets a property in the context.
     */
    public void set(String key, Object value) {
        properties.put(key, value);
    }

    /**
     * Merges all entries from the given map into this context.
     */
    public void mergeOutputs(Map<String, Object> outputs) {
        if (outputs != null) {
            properties.putAll(outputs);
        }
    }

    /**
     * Gets a property, returning null if absent.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) properties.get(key);
    }

    /**
     * Gets a property as an Optional.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptional(String key) {
        return Optional.ofNullable((T) properties.get(key));
    }

    /**
     * Gets a required property, throwing if absent.
     */
    @SuppressWarnings("unchecked")
    public <T> T getRequired(String key) {
        Object value = properties.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Required workflow context property '" + key + "' is missing for workflow=" + workflowId);
        }
        return (T) value;
    }

    /**
     * Checks if a property exists.
     */
    public boolean has(String key) {
        return properties.containsKey(key);
    }

    /**
     * Returns an unmodifiable snapshot of all properties.
     */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(properties));
    }

    @Override
    public String toString() {
        return "WorkflowContext{workflowId='" + workflowId + "', ticketKey='" + ticketKey +
                "', properties.size=" + properties.size() + "}";
    }
}
