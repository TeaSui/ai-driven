package com.aidriven.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRegistryTest {

    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
    }

    @Test
    void should_register_and_find_definition() {
        WorkflowDefinition def = new WorkflowDefinition("my-workflow", "desc",
                List.of(noopStep("s")));
        registry.register(def);

        Optional<WorkflowDefinition> found = registry.find("my-workflow");
        assertTrue(found.isPresent());
        assertEquals("my-workflow", found.get().workflowType());
    }

    @Test
    void should_return_empty_for_unknown_type() {
        assertTrue(registry.find("unknown").isEmpty());
    }

    @Test
    void should_throw_on_duplicate_registration() {
        WorkflowDefinition def = new WorkflowDefinition("dup", "d", List.of(noopStep("s")));
        registry.register(def);
        assertThrows(IllegalArgumentException.class, () -> registry.register(def));
    }

    @Test
    void should_allow_replace_via_registerOrReplace() {
        WorkflowDefinition v1 = new WorkflowDefinition("wf", "v1", List.of(noopStep("s")));
        WorkflowDefinition v2 = new WorkflowDefinition("wf", "v2", List.of(noopStep("s")));
        registry.register(v1);
        registry.registerOrReplace(v2);

        assertEquals("v2", registry.find("wf").get().description());
    }

    @Test
    void should_return_registered_types() {
        registry.register(new WorkflowDefinition("a", "A", List.of(noopStep("s"))));
        registry.register(new WorkflowDefinition("b", "B", List.of(noopStep("s"))));

        assertTrue(registry.registeredTypes().contains("a"));
        assertTrue(registry.registeredTypes().contains("b"));
        assertEquals(2, registry.size());
    }

    @Test
    void should_ignore_null_registration() {
        assertDoesNotThrow(() -> registry.register(null));
        assertEquals(0, registry.size());
    }

    private WorkflowStep noopStep(String id) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String description() { return "noop"; }
            @Override
            public WorkflowStepResult execute(
                    com.aidriven.spi.model.OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.success(id, java.util.Map.of());
            }
        };
    }
}
