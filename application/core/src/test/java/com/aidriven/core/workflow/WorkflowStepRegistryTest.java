package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowStepRegistryTest {

    private WorkflowStepRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowStepRegistry();
    }

    @Test
    void should_register_and_resolve_step() {
        WorkflowStep step = new TestStep("my-step");
        registry.register(step);

        Optional<WorkflowStep> resolved = registry.resolve("my-step");
        assertTrue(resolved.isPresent());
        assertSame(step, resolved.get());
    }

    @Test
    void should_return_empty_for_unknown_step() {
        Optional<WorkflowStep> resolved = registry.resolve("unknown");
        assertTrue(resolved.isEmpty());
    }

    @Test
    void should_ignore_null_registration() {
        assertDoesNotThrow(() -> registry.register(null));
        assertEquals(0, registry.size());
    }

    @Test
    void should_track_registered_step_ids() {
        registry.register(new TestStep("step-a"));
        registry.register(new TestStep("step-b"));

        assertTrue(registry.getRegisteredStepIds().contains("step-a"));
        assertTrue(registry.getRegisteredStepIds().contains("step-b"));
        assertEquals(2, registry.size());
    }

    private record TestStep(String stepId) implements WorkflowStep {
        @Override
        public String description() { return "Test step"; }

        @Override
        public WorkflowStepResult execute(OperationContext context, WorkflowContext wfContext) {
            return WorkflowStepResult.success();
        }
    }
}
