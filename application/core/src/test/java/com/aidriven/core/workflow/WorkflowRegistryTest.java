package com.aidriven.core.workflow;

import com.aidriven.core.workflow.steps.NoOpStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRegistryTest {

    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
    }

    @Test
    void register_and_find_workflow() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf1")
                .step(new NoOpStep("s1"))
                .build();
        registry.register(def);

        assertThat(registry.find("wf1")).isPresent();
        assertThat(registry.find("wf1").get().getWorkflowId()).isEqualTo("wf1");
    }

    @Test
    void find_returns_empty_for_unknown_workflow() {
        assertThat(registry.find("unknown")).isEmpty();
    }

    @Test
    void register_duplicate_throws_by_default() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf1")
                .step(new NoOpStep("s1"))
                .build();
        registry.register(def);

        assertThatThrownBy(() -> registry.register(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void register_with_allowOverwrite_replaces_existing() {
        WorkflowDefinition def1 = WorkflowDefinition.builder("wf1")
                .step(new NoOpStep("s1"))
                .build();
        WorkflowDefinition def2 = WorkflowDefinition.builder("wf1")
                .step(new NoOpStep("s1"))
                .step(new NoOpStep("s2"))
                .build();

        registry.register(def1);
        registry.register(def2, true);

        assertThat(registry.find("wf1").get().getStepOrder()).hasSize(2);
    }

    @Test
    void unregister_removes_workflow() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf1")
                .step(new NoOpStep("s1"))
                .build();
        registry.register(def);
        registry.unregister("wf1");

        assertThat(registry.find("wf1")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void contains_returns_correct_result() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf1")
                .step(new NoOpStep("s1"))
                .build();
        registry.register(def);

        assertThat(registry.contains("wf1")).isTrue();
        assertThat(registry.contains("other")).isFalse();
    }

    @Test
    void all_returns_all_registered_definitions() {
        registry.register(WorkflowDefinition.builder("wf1").step(new NoOpStep("s1")).build());
        registry.register(WorkflowDefinition.builder("wf2").step(new NoOpStep("s1")).build());

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.workflowIds()).containsExactlyInAnyOrder("wf1", "wf2");
    }
}
