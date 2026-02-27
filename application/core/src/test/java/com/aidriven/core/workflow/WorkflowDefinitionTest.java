package com.aidriven.core.workflow;

import com.aidriven.core.workflow.steps.NoOpStep;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionTest {

    @Test
    void builder_creates_valid_definition() {
        WorkflowDefinition def = WorkflowDefinition.builder("my-workflow")
                .description("Test workflow")
                .step(new NoOpStep("step1"))
                .step(new NoOpStep("step2"))
                .build();

        assertThat(def.getWorkflowId()).isEqualTo("my-workflow");
        assertThat(def.getDescription()).isEqualTo("Test workflow");
        assertThat(def.getStepOrder()).containsExactly("step1", "step2");
        assertThat(def.getSteps()).hasSize(2);
    }

    @Test
    void firstStepId_returns_first_step() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf")
                .step(new NoOpStep("alpha"))
                .step(new NoOpStep("beta"))
                .build();

        assertThat(def.firstStepId()).isEqualTo("alpha");
    }

    @Test
    void nextStepId_returns_correct_successor() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf")
                .step(new NoOpStep("a"))
                .step(new NoOpStep("b"))
                .step(new NoOpStep("c"))
                .build();

        assertThat(def.nextStepId("a")).isEqualTo("b");
        assertThat(def.nextStepId("b")).isEqualTo("c");
        assertThat(def.nextStepId("c")).isNull();
    }

    @Test
    void duplicate_step_id_throws() {
        assertThatThrownBy(() ->
                WorkflowDefinition.builder("wf")
                        .step(new NoOpStep("step1"))
                        .step(new NoOpStep("step1"))
                        .build()
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate step id");
    }

    @Test
    void empty_workflow_throws_on_build() {
        assertThatThrownBy(() ->
                WorkflowDefinition.builder("empty").build()
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one step");
    }

    @Test
    void getStep_throws_for_unknown_step() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf")
                .step(new NoOpStep("step1"))
                .build();

        assertThatThrownBy(() -> def.getStep("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void null_workflowId_throws() {
        assertThatThrownBy(() -> WorkflowDefinition.builder(null))
                .isInstanceOf(NullPointerException.class);
    }
}
