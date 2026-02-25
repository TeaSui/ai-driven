package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRegistryTest {

    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
    }

    @Test
    void should_register_and_find_workflow() {
        WorkflowDefinition definition = buildDefinition("ai-generate");
        registry.register(definition);

        Optional<WorkflowDefinition> found = registry.find("ai-generate");
        assertTrue(found.isPresent());
        assertEquals("ai-generate", found.get().getId());
    }

    @Test
    void should_return_empty_for_unknown_workflow() {
        Optional<WorkflowDefinition> found = registry.find("unknown");
        assertTrue(found.isEmpty());
    }

    @Test
    void should_throw_on_duplicate_registration() {
        WorkflowDefinition definition = buildDefinition("ai-generate");
        registry.register(definition);

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(buildDefinition("ai-generate")));
    }

    @Test
    void should_list_registered_ids() {
        registry.register(buildDefinition("workflow-a"));
        registry.register(buildDefinition("workflow-b"));

        assertTrue(registry.getRegisteredIds().contains("workflow-a"));
        assertTrue(registry.getRegisteredIds().contains("workflow-b"));
        assertEquals(2, registry.getRegisteredIds().size());
    }

    @Test
    void should_report_registered_status() {
        registry.register(buildDefinition("exists"));

        assertTrue(registry.isRegistered("exists"));
        assertFalse(registry.isRegistered("missing"));
    }

    @Test
    void should_ignore_null_registration() {
        assertDoesNotThrow(() -> registry.register(null));
        assertTrue(registry.getRegisteredIds().isEmpty());
    }

    private WorkflowDefinition buildDefinition(String id) {
        return WorkflowDefinition.builder()
                .id(id)
                .step(new WorkflowStep() {
                    @Override public String stepId() { return "noop"; }
                    @Override public String displayName() { return "No-op"; }
                    @Override
                    public WorkflowStepResult execute(OperationContext ctx, WorkflowState state) {
                        return WorkflowStepResult.success("noop", Map.of());
                    }
                })
                .build();
    }
}
