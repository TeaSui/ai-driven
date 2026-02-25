package com.aidriven.core.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowContextTest {

    @Test
    void should_store_and_retrieve_values() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "test");
        ctx.put("key", "value");

        Optional<String> result = ctx.getString("key");
        assertTrue(result.isPresent());
        assertEquals("value", result.get());
    }

    @Test
    void should_return_empty_for_missing_key() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "test");
        assertTrue(ctx.getString("missing").isEmpty());
    }

    @Test
    void should_throw_for_required_missing_key() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "test");
        assertThrows(IllegalStateException.class, () -> ctx.requireString("missing"));
    }

    @Test
    void should_remove_key_on_null_put() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "test");
        ctx.put("key", "value");
        ctx.put("key", null);
        assertFalse(ctx.has("key"));
    }

    @Test
    void should_seed_from_initial_data() {
        WorkflowContext ctx = WorkflowContext.of("wf-1", "test",
                Map.of("a", "1", "b", "2"));
        assertEquals("1", ctx.requireString("a"));
        assertEquals("2", ctx.requireString("b"));
    }

    @Test
    void should_return_unmodifiable_snapshot() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "test");
        ctx.put("x", "y");
        Map<String, Object> snapshot = ctx.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("z", "w"));
    }

    @Test
    void should_return_empty_optional_for_wrong_type() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "test");
        ctx.put("num", 42);
        Optional<String> result = ctx.getString("num");
        assertTrue(result.isEmpty());
    }

    @Test
    void should_putAll_entries() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "test");
        ctx.putAll(Map.of("a", "1", "b", "2"));
        assertTrue(ctx.has("a"));
        assertTrue(ctx.has("b"));
    }
}
