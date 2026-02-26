package com.aidriven.core.workflow;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable execution context passed between workflow steps.
 * Acts as a typed property bag for inter-step communication.
 *
 * <p>Thread-safety: NOT thread-safe. Workflows execute steps sequentially.
 */
@Slf4j
public class WorkflowContext {

    @Getter
    private final String workflowId;

    @Getter
    private final String ticketKey;

    private final Map<String, Object> properties = new HashMap<>();

    public WorkflowContext(String workflowId, String ticketKey) {
        this.workflowId = workflowId;
        this.ticketKey = ticketKey;
    }

    public WorkflowContext(String workflowId, String ticketKey, Map<String, Object> initialProperties) {
        this.workflowId = workflowId;
        this.ticketKey = ticketKey;
        if (initialProperties != null) {
            this.properties.putAll(initialProperties);
        }
    }

    /**
     * Sets a property value.
     */
    public WorkflowContext set(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    /**
     * Merges all entries from the given map into this context.
     */
    public WorkflowContext merge(Map<String, Object> values) {
        if (values != null) {
            properties.putAll(values);
        }
        return this;
    }

    /**
     * Gets a property value.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key) {
        return Optional.ofNullable((T) properties.get(key));
    }

    /**
     * Gets a required property value, throwing if absent.
     */
    @SuppressWarnings("unchecked")
    public <T> T require(String key) {
        Object value = properties.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Required workflow context property '" + key + "' is missing for workflow=" + workflowId);
        }
        return (T) value;
    }

    /**
     * Gets a string property value.
     */
    public Optional<String> getString(String key) {
        return get(key).map(Object::toString);
    }

    /**
     * Checks if a property exists.
     */
    public boolean has(String key) {
        return properties.containsKey(key);
    }

    /**
     * Returns an unmodifiable view of all properties.
     */
    public Map<String, Object> getAll() {
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public String toString() {
        return "WorkflowContext{workflowId='" + workflowId + "', ticketKey='" + ticketKey +
                "', properties=" + properties.keySet() + "}";
    }
}
