package com.aidriven.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowContextTest {

    private WorkflowContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new WorkflowContext("wf-123", "PROJ-1");
    }

    @Test
    void should_store_and_retrieve_value() {
        ctx.put("key", "value");
        Optional<String> result = ctx.get("key");
        assertTrue(result.isPresent());
        assertEquals("value", result.get());
    }

    @Test
    void should_return_empty_for_missing_key() {
        Optional<String> result = ctx.get("missing");
        assertTrue(result.isEmpty());
    }

    @Test
    void should_throw_for_required_missing_key() {
        assertThrows(IllegalStateException.class, () -> ctx.getRequired("missing"));
    }

    @Test
    void should_return_default_for_missing_string_key() {
        assertEquals("default", ctx.getString("missing", "default"));
    }

    @Test
    void should_remove_key_when_null_value_put() {
        ctx.put("key", "value");
        ctx.put("key", null);
        assertFalse(ctx.has("key"));
    }

    @Test
    void should_merge_all_entries_from_map() {
        ctx.putAll(Map.of("a", 1, "b", 2));
        assertEquals(1, ctx.<Integer>get("a").orElse(-1));
        assertEquals(2, ctx.<Integer>get("b").orElse(-1));
    }

    @Test
    void should_return_unmodifiable_snapshot() {
        ctx.put("x", "y");
        Map<String, Object> snapshot = ctx.snapshot();
        assertEquals("y", snapshot.get("x"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("z", "w"));
    }

    @Test
    void should_initialize_with_initial_data() {
        WorkflowContext ctxWithData = new WorkflowContext("wf-456", "PROJ-2",
                Map.of("preloaded", "data"));
        assertEquals("data", ctxWithData.getString("preloaded", null));
    }

    @Test
    void should_throw_on_null_key() {
        assertThrows(IllegalArgumentException.class, () -> ctx.put(null, "value"));
    }
}
