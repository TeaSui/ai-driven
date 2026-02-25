package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable execution context passed through all steps of a workflow.
 * Acts as a typed key-value store for inter-step data sharing.
 *
 * <p>Thread-safe for concurrent reads; writes should be done by a single
 * orchestrating thread between steps.
 */
@Slf4j
public class WorkflowContext {

    private final String workflowId;
    private final String ticketKey;
    private final Map<String, Object> data;

    public WorkflowContext(String workflowId, String ticketKey) {
        this.workflowId = workflowId;
        this.ticketKey = ticketKey;
        this.data = new ConcurrentHashMap<>();
    }

    public WorkflowContext(String workflowId, String ticketKey, Map<String, Object> initialData) {
        this.workflowId = workflowId;
        this.ticketKey = ticketKey;
        this.data = new ConcurrentHashMap<>(initialData != null ? initialData : Map.of());
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTicketKey() {
        return ticketKey;
    }

    /**
     * Stores a value in the context.
     */
    public void put(String key, Object value) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    /**
     * Merges all entries from the given map into this context.
     */
    public void putAll(Map<String, Object> entries) {
        if (entries != null) {
            entries.forEach(this::put);
        }
    }

    /**
     * Retrieves a value by key.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key) {
        return Optional.ofNullable((T) data.get(key));
    }

    /**
     * Retrieves a required value; throws if absent.
     */
    @SuppressWarnings("unchecked")
    public <T> T getRequired(String key) {
        Object value = data.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Required workflow context key '" + key + "' is missing for workflow=" + workflowId);
        }
        return (T) value;
    }

    /**
     * Returns a string value or the provided default.
     */
    public String getString(String key, String defaultValue) {
        return this.<String>get(key).orElse(defaultValue);
    }

    /**
     * Checks if a key is present.
     */
    public boolean has(String key) {
        return data.containsKey(key);
    }

    /**
     * Returns an unmodifiable snapshot of all context data.
     */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(data));
    }

    @Override
    public String toString() {
        return "WorkflowContext{workflowId='" + workflowId + "', ticketKey='" + ticketKey +
                "', keys=" + data.keySet() + "}";
    }
}
