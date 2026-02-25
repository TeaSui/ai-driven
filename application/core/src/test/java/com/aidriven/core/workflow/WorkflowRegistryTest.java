package com.aidriven.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRegistryTest {

    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
    }

    @Test
    void should_register_and_retrieve_workflow() {
        WorkflowDefinition def = buildDef("wf-1");
        registry.register(def);

        Optional<WorkflowDefinition> result = registry.get("wf-1");
        assertTrue(result.isPresent());
        assertEquals("wf-1", result.get().getWorkflowId());
    }

    @Test
    void should_return_empty_for_unknown_workflow() {
        assertTrue(registry.get("unknown").isEmpty());
    }

    @Test
    void should_overwrite_existing_workflow() {
        WorkflowDefinition def1 = buildDef("wf-1");
        WorkflowDefinition def2 = WorkflowDefinition.builder("wf-1")
                .displayName("Updated")
                .step(noopStep("s"))
                .build();

        registry.register(def1);
        registry.register(def2);

        assertEquals("Updated", registry.get("wf-1").get().getDisplayName());
    }

    @Test
    void should_list_all_registered_ids() {
        registry.register(buildDef("wf-a"));
        registry.register(buildDef("wf-b"));

        assertTrue(registry.getRegisteredIds().contains("wf-a"));
        assertTrue(registry.getRegisteredIds().contains("wf-b"));
        assertEquals(2, registry.getRegisteredIds().size());
    }

    @Test
    void should_unregister_workflow() {
        registry.register(buildDef("wf-1"));
        registry.unregister("wf-1");

        assertFalse(registry.contains("wf-1"));
    }

    @Test
    void should_handle_null_registration_gracefully() {
        assertDoesNotThrow(() -> registry.register(null));
        assertEquals(0, registry.getRegisteredIds().size());
    }

    @Test
    void should_return_unmodifiable_all_map() {
        registry.register(buildDef("wf-1"));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.getAll().put("new", buildDef("new")));
    }

    private WorkflowDefinition buildDef(String id) {
        return WorkflowDefinition.builder(id)
                .displayName("Workflow " + id)
                .step(noopStep("step-1"))
                .build();
    }

    private WorkflowStep noopStep(String id) {
        return new WorkflowStep() {
            @Override
            public String stepId() { return id; }
            @Override
            public String displayName() { return "Noop"; }
            @Override
            public WorkflowStepResult execute(
                    com.aidriven.spi.model.OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.success(id, java.util.Map.of());
            }
        };
    }
}
