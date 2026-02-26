package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEngineTest {

    private WorkflowStepRegistry stepRegistry;
    private WorkflowDefinitionRegistry definitionRegistry;
    private WorkflowEngine engine;
    private OperationContext operationContext;

    @BeforeEach
    void setUp() {
        stepRegistry = new WorkflowStepRegistry();
        definitionRegistry = new WorkflowDefinitionRegistry();
        engine = new WorkflowEngine(definitionRegistry, stepRegistry);
        operationContext = OperationContext.builder().tenantId("test-tenant").userId("test-user").build();
    }

    @Test
    void should_execute_single_step_workflow_successfully() {
        stepRegistry.register(new SuccessStep("step-a", Map.of("output", "value-a")));
        definitionRegistry.register(SimpleWorkflowDefinition.of(
                "wf-1", "Test Workflow", "A test", List.of("step-a")));

        WorkflowExecutionResult result = engine.execute("wf-1", "PROJ-1", operationContext, Map.of());

        assertTrue(result.success());
        assertEquals(List.of("step-a"), result.completedSteps());
        assertNull(result.failedStep());
        assertEquals("value-a", result.outputs().get("output"));
    }

    @Test
    void should_execute_multi_step_workflow_and_pass_context() {
        stepRegistry.register(new SuccessStep("step-a", Map.of("key1", "val1")));
        stepRegistry.register(new ContextReadingStep("step-b", "key1"));
        definitionRegistry.register(SimpleWorkflowDefinition.of(
                "wf-2", "Multi Step", "Two steps", List.of("step-a", "step-b")));

        WorkflowExecutionResult result = engine.execute("wf-2", "PROJ-2", operationContext, Map.of());

        assertTrue(result.success());
        assertEquals(2, result.completedSteps().size());
        assertEquals("val1", result.outputs().get("read_value"));
    }

    @Test
    void should_fail_when_step_returns_failure() {
        stepRegistry.register(new SuccessStep("step-a", Map.of()));
        stepRegistry.register(new FailingStep("step-b", "Something went wrong"));
        stepRegistry.register(new SuccessStep("step-c", Map.of()));
        definitionRegistry.register(SimpleWorkflowDefinition.of(
                "wf-3", "Failing Workflow", "Fails at step-b",
                List.of("step-a", "step-b", "step-c")));

        WorkflowExecutionResult result = engine.execute("wf-3", "PROJ-3", operationContext, Map.of());

        assertFalse(result.success());
        assertEquals("step-b", result.failedStep());
        assertEquals("Something went wrong", result.errorMessage());
        assertEquals(List.of("step-a"), result.completedSteps());
    }

    @Test
    void should_fail_when_step_throws_exception() {
        stepRegistry.register(new ThrowingStep("step-a", "Unexpected error"));
        definitionRegistry.register(SimpleWorkflowDefinition.of(
                "wf-4", "Throwing Workflow", "Throws", List.of("step-a")));

        WorkflowExecutionResult result = engine.execute("wf-4", "PROJ-4", operationContext, Map.of());

        assertFalse(result.success());
        assertEquals("step-a", result.failedStep());
        assertTrue(result.errorMessage().contains("Unexpected error"));
    }

    @Test
    void should_fail_when_workflow_not_found() {
        WorkflowExecutionResult result = engine.execute("unknown-wf", "PROJ-5", operationContext, Map.of());

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Unknown workflow"));
        assertNull(result.failedStep());
    }

    @Test
    void should_fail_when_step_not_registered() {
        definitionRegistry.register(SimpleWorkflowDefinition.of(
                "wf-5", "Missing Step", "Step not registered", List.of("missing-step")));

        WorkflowExecutionResult result = engine.execute("wf-5", "PROJ-6", operationContext, Map.of());

        assertFalse(result.success());
        assertEquals("missing-step", result.failedStep());
        assertTrue(result.errorMessage().contains("Step not found"));
    }

    @Test
    void should_skip_idempotent_step_when_output_already_present() {
        stepRegistry.register(new IdempotentStep("step-a", "existing_key"));
        definitionRegistry.register(SimpleWorkflowDefinition.of(
                "wf-6", "Idempotent Workflow", "Skips step", List.of("step-a")));

        // Pre-populate context with the key the step would produce
        WorkflowExecutionResult result = engine.execute(
                "wf-6", "PROJ-7", operationContext, Map.of("existing_key", "already-there"));

        assertTrue(result.success());
        assertEquals(List.of("step-a"), result.completedSteps());
    }

    @Test
    void should_include_initial_inputs_in_outputs() {
        stepRegistry.register(new SuccessStep("step-a", Map.of()));
        definitionRegistry.register(SimpleWorkflowDefinition.of(
                "wf-7", "Input Workflow", "Passes inputs", List.of("step-a")));

        WorkflowExecutionResult result = engine.execute(
                "wf-7", "PROJ-8", operationContext, Map.of("ticketId", "12345"));

        assertTrue(result.success());
        assertEquals("12345", result.outputs().get("ticketId"));
    }

    @Test
    void should_record_duration() {
        stepRegistry.register(new SuccessStep("step-a", Map.of()));
        definitionRegistry.register(SimpleWorkflowDefinition.of(
                "wf-8", "Duration Workflow", "Measures time", List.of("step-a")));

        WorkflowExecutionResult result = engine.execute("wf-8", "PROJ-9", operationContext, Map.of());

        assertNotNull(result.startedAt());
        assertNotNull(result.completedAt());
        assertFalse(result.duration().isNegative());
    }

    // --- Test step implementations ---

    private record SuccessStep(String stepId, Map<String, Object> outputs) implements WorkflowStep {
        @Override
        public String description() { return "Always succeeds"; }

        @Override
        public WorkflowStepResult execute(OperationContext context, WorkflowContext wfContext) {
            return WorkflowStepResult.success(outputs);
        }
    }

    private record FailingStep(String stepId, String errorMessage) implements WorkflowStep {
        @Override
        public String description() { return "Always fails"; }

        @Override
        public WorkflowStepResult execute(OperationContext context, WorkflowContext wfContext) {
            return WorkflowStepResult.failure(errorMessage);
        }
    }

    private record ThrowingStep(String stepId, String message) implements WorkflowStep {
        @Override
        public String description() { return "Always throws"; }

        @Override
        public WorkflowStepResult execute(OperationContext context, WorkflowContext wfContext) {
            throw new RuntimeException(message);
        }
    }

    private record ContextReadingStep(String stepId, String keyToRead) implements WorkflowStep {
        @Override
        public String description() { return "Reads from context"; }

        @Override
        public WorkflowStepResult execute(OperationContext context, WorkflowContext wfContext) {
            String value = wfContext.getString(keyToRead).orElse("not-found");
            return WorkflowStepResult.success(Map.of("read_value", value));
        }
    }

    private record IdempotentStep(String stepId, String outputKey) implements WorkflowStep {
        @Override
        public String description() { return "Idempotent step"; }

        @Override
        public boolean isIdempotent() { return true; }

        @Override
        public WorkflowStepResult execute(OperationContext context, WorkflowContext wfContext) {
            if (wfContext.has(outputKey)) {
                return WorkflowStepResult.skipped();
            }
            return WorkflowStepResult.success(Map.of(outputKey, "computed"));
        }
    }
}
