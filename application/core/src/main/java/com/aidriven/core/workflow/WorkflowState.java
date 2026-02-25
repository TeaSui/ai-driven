package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable state bag passed between workflow steps.
 * Steps read inputs from prior steps and write their outputs here.
 *
 * <p>Thread-safety: not thread-safe. Workflows execute steps sequentially.
 */
@Slf4j
public class WorkflowState {

    private final Map<String, Object> data = new HashMap<>();
    private final String workflowId;
    private final String ticketKey;

    public WorkflowState(String workflowId, String ticketKey) {
        this.workflowId = workflowId;
        this.ticketKey = ticketKey;
    }

    /** Returns the workflow execution ID. */
    public String getWorkflowId() {
        return workflowId;
    }

    /** Returns the Jira ticket key associated with this workflow run. */
    public String getTicketKey() {
        return ticketKey;
    }

    /**
     * Stores a value under the given key.
     *
     * @param key   State key
     * @param value Value to store (must be serializable for persistence)
     */
    public void put(String key, Object value) {
        data.put(key, value);
        log.debug("WorkflowState[{}] set: {}", workflowId, key);
    }

    /**
     * Retrieves a value by key.
     *
     * @param key State key
     * @return Optional containing the value, or empty if absent
     */
    public Optional<Object> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    /**
     * Retrieves a typed value by key.
     *
     * @param key  State key
     * @param type Expected type
     * @return Optional containing the typed value, or empty if absent or wrong type
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = data.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            log.warn("WorkflowState[{}] type mismatch for key '{}': expected {} but got {}",
                    workflowId, key, type.getSimpleName(), value.getClass().getSimpleName());
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
    }

    /**
     * Retrieves a required string value.
     *
     * @param key State key
     * @return The string value
     * @throws IllegalStateException if the key is absent or not a String
     */
    public String getRequired(String key) {
        return get(key, String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "Required workflow state key '" + key + "' is missing in workflow " + workflowId));
    }

    /**
     * Merges all outputs from a step result into this state.
     *
     * @param result The step result whose outputs to merge
     */
    public void mergeOutputs(WorkflowStepResult result) {
        if (result.outputs() != null) {
            data.putAll(result.outputs());
        }
    }

    /**
     * Returns an unmodifiable snapshot of the current state.
     */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(data));
    }

    /**
     * Returns true if the given key is present in the state.
     */
    public boolean contains(String key) {
        return data.containsKey(key);
    }
}
