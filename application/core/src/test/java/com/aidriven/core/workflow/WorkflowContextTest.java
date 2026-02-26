package com.aidriven.core.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowContextTest {

    @Test
    void should_store_and_retrieve_values() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");
        ctx.set("key", "value");

        Optional<String> result = ctx.get("key");
        assertTrue(result.isPresent());
        assertEquals("value", result.get());
    }

    @Test
    void should_return_empty_for_missing_key() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");

        Optional<Object> result = ctx.get("missing");
        assertTrue(result.isEmpty());
    }

    @Test
    void should_throw_for_required_missing_key() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");

        assertThrows(IllegalStateException.class, () -> ctx.require("missing"));
    }

    @Test
    void should_merge_map_into_context() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");
        ctx.merge(Map.of("a", 1, "b", "two"));

        assertEquals(1, ctx.<Integer>get("a").orElseThrow());
        assertEquals("two", ctx.getString("b").orElseThrow());
    }

    @Test
    void should_initialize_with_initial_properties() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1", Map.of("init", "value"));

        assertTrue(ctx.has("init"));
        assertEquals("value", ctx.getString("init").orElseThrow());
    }

    @Test
    void should_return_unmodifiable_view() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");
        ctx.set("key", "value");

        Map<String, Object> all = ctx.getAll();
        assertThrows(UnsupportedOperationException.class, () -> all.put("new", "value"));
    }

    @Test
    void should_support_method_chaining() {
        WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");
        ctx.set("a", 1).set("b", 2).merge(Map.of("c", 3));

        assertTrue(ctx.has("a"));
        assertTrue(ctx.has("b"));
        assertTrue(ctx.has("c"));
    }
}
