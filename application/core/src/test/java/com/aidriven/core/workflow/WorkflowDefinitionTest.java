package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowDefinitionTest {

    @Test
    void should_build_definition_with_required_fields() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("test-workflow")
                .step(noopStep())
                .build();

        assertEquals("test-workflow", def.getId());
        assertEquals(1, def.getSteps().size());
        assertTrue(def.isHaltOnStepFailure()); // default
    }

    @Test
    void should_use_id_as_display_name_when_not_set() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("my-workflow")
                .step(noopStep())
                .build();

        assertEquals("my-workflow", def.getDisplayName());
    }

    @Test
    void should_use_custom_display_name() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("my-workflow")
                .displayName("My Custom Workflow")
                .step(noopStep())
                .build();

        assertEquals("My Custom Workflow", def.getDisplayName());
    }

    @Test
    void should_configure_halt_on_failure() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("test")
                .haltOnStepFailure(false)
                .step(noopStep())
                .build();

        assertFalse(def.isHaltOnStepFailure());
    }

    @Test
    void should_throw_when_no_steps() {
        assertThrows(IllegalStateException.class, () ->
                WorkflowDefinition.builder().id("empty").build());
    }

    @Test
    void should_throw_on_null_step() {
        assertThrows(NullPointerException.class, () ->
                WorkflowDefinition.builder().id("test").step(null));
    }

    @Test
    void should_return_immutable_steps_list() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("test")
                .step(noopStep())
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> def.getSteps().add(noopStep()));
    }

    @Test
    void should_add_multiple_steps_via_steps_method() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("multi")
                .steps(java.util.List.of(noopStep(), noopStep(), noopStep()))
                .build();

        assertEquals(3, def.getSteps().size());
    }

    private WorkflowStep noopStep() {
        return new WorkflowStep() {
            @Override public String stepId() { return "noop-" + System.nanoTime(); }
            @Override public String displayName() { return "No-op"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowState state) {
                return WorkflowStepResult.success(stepId(), Map.of());
            }
        };
    }
}
