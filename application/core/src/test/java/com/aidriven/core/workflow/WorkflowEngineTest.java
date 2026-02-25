package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEngineTest {

    private WorkflowEngine engine;
    private OperationContext context;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngine();
        context = OperationContext.builder().tenantId("test-tenant").userId("test-user").build();
    }

    // --- Happy path ---

    @Test
    void should_execute_all_steps_in_order() {
        java.util.List<String> executionOrder = new java.util.ArrayList<>();

        WorkflowStep step1 = recordingStep("step1", executionOrder);
        WorkflowStep step2 = recordingStep("step2", executionOrder);
        WorkflowStep step3 = recordingStep("step3", executionOrder);

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("test-workflow")
                .step(step1)
                .step(step2)
                .step(step3)
                .build();

        WorkflowExecution execution = engine.execute(definition, context, "PROJ-1");

        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertEquals(3, execution.getStepResults().size());
        assertEquals(java.util.List.of("step1", "step2", "step3"), executionOrder);
    }

    @Test
    void should_propagate_state_between_steps() {
        WorkflowStep producer = new WorkflowStep() {
            @Override public String stepId() { return "producer"; }
            @Override public String displayName() { return "Producer"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowState state) {
                return WorkflowStepResult.success("producer", Map.of("key", "value-from-producer"));
            }
        };

        String[] capturedValue = {null};
        WorkflowStep consumer = new WorkflowStep() {
            @Override public String stepId() { return "consumer"; }
            @Override public String displayName() { return "Consumer"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowState state) {
                capturedValue[0] = state.get("key", String.class).orElse(null);
                return WorkflowStepResult.success("consumer", Map.of());
            }
        };

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("state-test")
                .step(producer)
                .step(consumer)
                .build();

        engine.execute(definition, context, "PROJ-1");

        assertEquals("value-from-producer", capturedValue[0]);
    }

    @Test
    void should_complete_with_correct_ticket_key() {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("simple")
                .step(successStep("only-step"))
                .build();

        WorkflowExecution execution = engine.execute(definition, context, "PROJ-42");

        assertEquals("PROJ-42", execution.getTicketKey());
        assertEquals("simple", execution.getWorkflowId());
        assertNotNull(execution.getExecutionId());
    }

    // --- Failure handling ---

    @Test
    void should_halt_on_step_failure_by_default() {
        java.util.List<String> executionOrder = new java.util.ArrayList<>();

        WorkflowStep step1 = recordingStep("step1", executionOrder);
        WorkflowStep failingStep = failingStep("step2");
        WorkflowStep step3 = recordingStep("step3", executionOrder);

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("halt-test")
                .step(step1)
                .step(failingStep)
                .step(step3)
                .build();

        WorkflowExecution execution = engine.execute(definition, context, "PROJ-1");

        assertEquals(WorkflowExecutionStatus.FAILED, execution.getStatus());
        assertEquals(java.util.List.of("step1"), executionOrder); // step3 never ran
        assertNotNull(execution.getErrorMessage());
        assertTrue(execution.getErrorMessage().contains("step2"));
    }

    @Test
    void should_continue_on_failure_when_configured() {
        java.util.List<String> executionOrder = new java.util.ArrayList<>();

        WorkflowStep step1 = recordingStep("step1", executionOrder);
        WorkflowStep failingStep = failingStep("step2");
        WorkflowStep step3 = recordingStep("step3", executionOrder);

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("continue-test")
                .haltOnStepFailure(false)
                .step(step1)
                .step(failingStep)
                .step(step3)
                .build();

        WorkflowExecution execution = engine.execute(definition, context, "PROJ-1");

        // Execution completes (not failed) because haltOnStepFailure=false
        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertEquals(java.util.List.of("step1", "step3"), executionOrder);
        assertFalse(execution.allStepsSucceeded()); // step2 still failed
    }

    @Test
    void should_handle_non_retryable_step_exception() {
        WorkflowStep nonRetryableStep = new WorkflowStep() {
            @Override public String stepId() { return "non-retryable"; }
            @Override public String displayName() { return "Non-Retryable"; }
            @Override public boolean isRetryable() { return false; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowState state)
                    throws WorkflowStepException {
                throw new WorkflowStepException("non-retryable", "Fatal error");
            }
        };

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("non-retryable-test")
                .step(nonRetryableStep)
                .build();

        WorkflowExecution execution = engine.execute(definition, context, "PROJ-1");

        assertEquals(WorkflowExecutionStatus.FAILED, execution.getStatus());
        assertEquals(1, execution.getStepResults().size());
        assertTrue(execution.getStepResults().get(0).isFailed());
        assertEquals("Fatal error", execution.getStepResults().get(0).message());
    }

    // --- Skipped steps ---

    @Test
    void should_record_skipped_steps_without_failing() {
        WorkflowStep skippedStep = new WorkflowStep() {
            @Override public String stepId() { return "skipped"; }
            @Override public String displayName() { return "Skipped"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowState state) {
                return WorkflowStepResult.skipped("skipped", "dry-run mode");
            }
        };

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("skip-test")
                .step(skippedStep)
                .build();

        WorkflowExecution execution = engine.execute(definition, context, "PROJ-1");

        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertTrue(execution.getStepResults().get(0).isSkipped());
    }

    // --- Execution metadata ---

    @Test
    void should_record_duration() throws InterruptedException {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("duration-test")
                .step(successStep("step1"))
                .build();

        WorkflowExecution execution = engine.execute(definition, context, "PROJ-1");

        assertTrue(execution.getDurationMs() >= 0);
        assertNotNull(execution.getStartedAt());
        assertNotNull(execution.getCompletedAt());
    }

    // --- Null safety ---

    @Test
    void should_throw_on_null_definition() {
        assertThrows(NullPointerException.class,
                () -> engine.execute(null, context, "PROJ-1"));
    }

    @Test
    void should_throw_on_null_context() {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("null-test")
                .step(successStep("step1"))
                .build();
        assertThrows(NullPointerException.class,
                () -> engine.execute(definition, null, "PROJ-1"));
    }

    // --- Helpers ---

    private WorkflowStep successStep(String id) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String displayName() { return id; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowState state) {
                return WorkflowStepResult.success(id, Map.of());
            }
        };
    }

    private WorkflowStep recordingStep(String id, java.util.List<String> order) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String displayName() { return id; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowState state) {
                order.add(id);
                return WorkflowStepResult.success(id, Map.of());
            }
        };
    }

    private WorkflowStep failingStep(String id) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String displayName() { return id; }
            @Override public boolean isRetryable() { return false; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowState state) {
                return WorkflowStepResult.failed(id, "Simulated failure");
            }
        };
    }
}
