package com.aidriven.core.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable execution context shared across all steps in a single workflow run.
 *
 * <p>Steps read their inputs and write their outputs through this context.
 * It acts as a typed key-value store scoped to one workflow execution.
 *
 * <p>Thread-safety: uses {@link ConcurrentHashMap} to support parallel step
 * execution in future extensions.
 */
@Slf4j
public class WorkflowContext {

    /** Well-known context keys used by built-in steps. */
    public static final String KEY_TICKET_KEY = "ticketKey";
    public static final String KEY_TICKET_INFO = "ticketInfo";
    public static final String KEY_PLATFORM = "platform";
    public static final String KEY_BRANCH_NAME = "branchName";
    public static final String KEY_PR_URL = "prUrl";
    public static final String KEY_PR_ID = "prId";
    public static final String KEY_CODE_CONTEXT = "codeContext";
    public static final String KEY_GENERATED_FILES = "generatedFiles";
    public static final String KEY_COMMIT_MESSAGE = "commitMessage";
    public static final String KEY_DRY_RUN = "dryRun";

    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private final String workflowId;
    private final String workflowType;

    public WorkflowContext(String workflowId, String workflowType) {
        this.workflowId = workflowId;
        this.workflowType = workflowType;
    }

    /**
     * Creates a WorkflowContext pre-populated with initial data.
     */
    public static WorkflowContext of(String workflowId, String workflowType, Map<String, Object> initialData) {
        WorkflowContext ctx = new WorkflowContext(workflowId, workflowType);
        if (initialData != null) {
            ctx.data.putAll(initialData);
        }
        return ctx;
    }

    /** Stores a value in the context. */
    public WorkflowContext put(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
        return this;
    }

    /** Stores all entries from the given map. */
    public WorkflowContext putAll(Map<String, Object> entries) {
        if (entries != null) {
            entries.forEach(this::put);
        }
        return this;
    }

    /** Retrieves a value, returning empty if absent or wrong type. */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = data.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            log.warn("WorkflowContext key '{}' has type {} but expected {}",
                    key, value.getClass().getSimpleName(), type.getSimpleName());
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
    }

    /** Retrieves a required value, throwing if absent. */
    public <T> T require(String key, Class<T> type) {
        return get(key, type).orElseThrow(() ->
                new IllegalStateException("Required workflow context key '" + key + "' is missing"));
    }

    /** Retrieves a String value. */
    public Optional<String> getString(String key) {
        return get(key, String.class);
    }

    /** Retrieves a required String value. */
    public String requireString(String key) {
        return require(key, String.class);
    }

    /** Checks whether a key is present. */
    public boolean has(String key) {
        return data.containsKey(key);
    }

    /** Returns an unmodifiable snapshot of all context data. */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(data));
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    @Override
    public String toString() {
        return "WorkflowContext{workflowId='" + workflowId + "', workflowType='" + workflowType +
                "', keys=" + data.keySet() + "}";
    }
}
