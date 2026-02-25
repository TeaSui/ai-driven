package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowDefinitionTest {

    @Test
    void should_build_definition_with_required_fields() {
        WorkflowDefinition def = WorkflowDefinition.builder("my-wf")
                .displayName("My Workflow")
                .step(noopStep("s1"))
                .build();

        assertEquals("my-wf", def.getWorkflowId());
        assertEquals("My Workflow", def.getDisplayName());
        assertEquals(1, def.getSteps().size());
        assertTrue(def.isStopOnFirstFailure()); // default
    }

    @Test
    void should_default_display_name_to_workflow_id() {
        WorkflowDefinition def = WorkflowDefinition.builder("my-wf")
                .step(noopStep("s1"))
                .build();

        assertEquals("my-wf", def.getDisplayName());
    }

    @Test
    void should_throw_when_no_steps_provided() {
        assertThrows(IllegalStateException.class,
                () -> WorkflowDefinition.builder("empty-wf").build());
    }

    @Test
    void should_throw_on_null_step() {
        assertThrows(NullPointerException.class,
                () -> WorkflowDefinition.builder("wf").step(null));
    }

    @Test
    void should_configure_stop_on_first_failure_false() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf")
                .step(noopStep("s1"))
                .stopOnFirstFailure(false)
                .build();

        assertFalse(def.isStopOnFirstFailure());
    }

    @Test
    void should_return_unmodifiable_steps_list() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf")
                .step(noopStep("s1"))
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> def.getSteps().add(noopStep("s2")));
    }

    private WorkflowStep noopStep(String id) {
        return new WorkflowStep() {
            @Override
            public String stepId() { return id; }
            @Override
            public String displayName() { return "Noop " + id; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.success(id, Map.of());
            }
        };
    }
}
