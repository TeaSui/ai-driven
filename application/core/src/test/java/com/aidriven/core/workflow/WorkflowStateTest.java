package com.aidriven.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowStateTest {

    private WorkflowState state;

    @BeforeEach
    void setUp() {
        state = new WorkflowState("exec-123", "PROJ-1");
    }

    @Test
    void should_store_and_retrieve_value() {
        state.put("key", "value");
        Optional<Object> result = state.get("key");
        assertTrue(result.isPresent());
        assertEquals("value", result.get());
    }

    @Test
    void should_return_empty_for_missing_key() {
        assertTrue(state.get("missing").isEmpty());
    }

    @Test
    void should_retrieve_typed_value() {
        state.put("count", 42);
        Optional<Integer> result = state.get("count", Integer.class);
        assertTrue(result.isPresent());
        assertEquals(42, result.get());
    }

    @Test
    void should_return_empty_on_type_mismatch() {
        state.put("key", 42);
        Optional<String> result = state.get("key", String.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_throw_on_missing_required_key() {
        assertThrows(IllegalStateException.class, () -> state.getRequired("missing"));
    }

    @Test
    void should_return_required_value_when_present() {
        state.put("ticketKey", "PROJ-1");
        assertEquals("PROJ-1", state.getRequired("ticketKey"));
    }

    @Test
    void should_merge_step_outputs() {
        WorkflowStepResult result = WorkflowStepResult.success("step1",
                Map.of("prUrl", "https://github.com/pr/1", "branch", "ai/PROJ-1"));
        state.mergeOutputs(result);

        assertEquals("https://github.com/pr/1", state.get("prUrl", String.class).orElse(null));
        assertEquals("ai/PROJ-1", state.get("branch", String.class).orElse(null));
    }

    @Test
    void should_not_merge_null_outputs() {
        WorkflowStepResult result = WorkflowStepResult.failed("step1", "error");
        assertDoesNotThrow(() -> state.mergeOutputs(result));
    }

    @Test
    void should_return_snapshot() {
        state.put("a", "1");
        state.put("b", "2");
        Map<String, Object> snapshot = state.snapshot();
        assertEquals(2, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("c", "3"));
    }

    @Test
    void should_report_contains() {
        state.put("present", "yes");
        assertTrue(state.contains("present"));
        assertFalse(state.contains("absent"));
    }

    @Test
    void should_expose_workflow_id_and_ticket_key() {
        assertEquals("exec-123", state.getWorkflowId());
        assertEquals("PROJ-1", state.getTicketKey());
    }
}
