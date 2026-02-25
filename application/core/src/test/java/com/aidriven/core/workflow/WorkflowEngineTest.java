package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;
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

    // ─── Happy Path ───

    @Test
    void should_execute_single_step_successfully() {
        WorkflowStep step = successStep("step-1", Map.of("output", "value1"));
        WorkflowDefinition def = WorkflowDefinition.builder("test-wf")
                .step(step)
                .build();

        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-1"));

        assertTrue(result.isSuccess());
        assertEquals("PROJ-1", result.ticketKey());
        assertEquals(1, result.stepResults().size());
        assertTrue(result.stepResults().get(0).isSuccess());
        assertEquals("value1", result.finalOutputs().get("output"));
    }

    @Test
    void should_chain_outputs_between_steps() {
        WorkflowStep step1 = successStep("step-1", Map.of("key1", "from-step1"));
        WorkflowStep step2 = new WorkflowStep() {
            @Override
            public String stepId() { return "step-2"; }
            @Override
            public String displayName() { return "Step 2"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                String upstream = wfCtx.getString("key1", "missing");
                return WorkflowStepResult.success("step-2", Map.of("key2", upstream + "-processed"));
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("chain-wf")
                .step(step1)
                .step(step2)
                .build();

        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-2"));

        assertTrue(result.isSuccess());
        assertEquals("from-step1-processed", result.finalOutputs().get("key2"));
        assertEquals(2, result.successfulStepCount());
    }

    @Test
    void should_include_initial_inputs_in_context() {
        WorkflowStep step = new WorkflowStep() {
            @Override
            public String stepId() { return "check-input"; }
            @Override
            public String displayName() { return "Check Input"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                String val = wfCtx.getString("initialParam", null);
                return "hello".equals(val)
                        ? WorkflowStepResult.success("check-input", Map.of())
                        : WorkflowStepResult.failed("check-input", "missing initialParam");
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("input-wf").step(step).build();
        WorkflowResult result = engine.execute(def, context,
                Map.of("ticketKey", "PROJ-3", "initialParam", "hello"));

        assertTrue(result.isSuccess());
    }

    // ─── Failure Handling ───

    @Test
    void should_stop_on_first_failure_by_default() {
        WorkflowStep step1 = failingStep("step-1", "step 1 failed");
        WorkflowStep step2 = successStep("step-2", Map.of());

        WorkflowDefinition def = WorkflowDefinition.builder("fail-wf")
                .step(step1)
                .step(step2)
                .build();

        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-4"));

        assertTrue(result.isFailed());
        assertEquals(1, result.stepResults().size(), "Should stop after first failure");
        assertEquals("step 1 failed", result.errorMessage());
    }

    @Test
    void should_continue_after_failure_when_stopOnFirstFailure_is_false() {
        WorkflowStep step1 = failingStep("step-1", "step 1 failed");
        WorkflowStep step2 = successStep("step-2", Map.of("done", true));

        WorkflowDefinition def = WorkflowDefinition.builder("continue-wf")
                .stopOnFirstFailure(false)
                .step(step1)
                .step(step2)
                .build();

        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-5"));

        assertEquals(WorkflowResult.WorkflowStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(2, result.stepResults().size());
        assertEquals(1, result.successfulStepCount());
        assertEquals(1, result.failedStepCount());
    }

    @Test
    void should_handle_step_throwing_exception() {
        WorkflowStep step = new WorkflowStep() {
            @Override
            public String stepId() { return "throwing-step"; }
            @Override
            public String displayName() { return "Throwing Step"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx)
                    throws WorkflowStepException {
                throw new WorkflowStepException("throwing-step", "unexpected error");
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("exception-wf").step(step).build();
        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-6"));

        assertTrue(result.isFailed());
        assertTrue(result.errorMessage().contains("unexpected error"));
    }

    // ─── Optional Steps ───

    @Test
    void should_continue_after_optional_step_failure() {
        WorkflowStep optionalStep = new WorkflowStep() {
            @Override
            public String stepId() { return "optional-step"; }
            @Override
            public String displayName() { return "Optional Step"; }
            @Override
            public boolean isOptional() { return true; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.failed("optional-step", "optional failed");
            }
        };
        WorkflowStep mandatoryStep = successStep("mandatory-step", Map.of("result", "ok"));

        WorkflowDefinition def = WorkflowDefinition.builder("optional-wf")
                .step(optionalStep)
                .step(mandatoryStep)
                .build();

        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-7"));

        // Optional step failed but mandatory step succeeded → PARTIAL_SUCCESS
        assertEquals(WorkflowResult.WorkflowStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(2, result.stepResults().size());
    }

    // ─── Skipped Steps ───

    @Test
    void should_handle_skipped_steps() {
        WorkflowStep skippedStep = new WorkflowStep() {
            @Override
            public String stepId() { return "skipped-step"; }
            @Override
            public String displayName() { return "Skipped Step"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.skipped("skipped-step", "precondition not met");
            }
        };
        WorkflowStep nextStep = successStep("next-step", Map.of());

        WorkflowDefinition def = WorkflowDefinition.builder("skip-wf")
                .step(skippedStep)
                .step(nextStep)
                .build();

        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-8"));

        assertTrue(result.isSuccess());
        assertEquals(2, result.stepResults().size());
        assertTrue(result.stepResults().get(0).isSkipped());
    }

    // ─── Retry ───

    @Test
    void should_retry_step_on_failure_and_succeed() {
        int[] callCount = {0};
        WorkflowStep retryableStep = new WorkflowStep() {
            @Override
            public String stepId() { return "retry-step"; }
            @Override
            public String displayName() { return "Retry Step"; }
            @Override
            public int maxRetries() { return 2; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                callCount[0]++;
                if (callCount[0] < 3) {
                    return WorkflowStepResult.failed("retry-step", "attempt " + callCount[0] + " failed");
                }
                return WorkflowStepResult.success("retry-step", Map.of("attempts", callCount[0]));
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("retry-wf").step(retryableStep).build();
        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-9"));

        assertTrue(result.isSuccess());
        assertEquals(3, callCount[0]);
    }

    // ─── Duration ───

    @Test
    void should_record_start_and_completion_times() {
        WorkflowStep step = successStep("step-1", Map.of());
        WorkflowDefinition def = WorkflowDefinition.builder("timing-wf").step(step).build();

        WorkflowResult result = engine.execute(def, context, Map.of("ticketKey", "PROJ-10"));

        assertNotNull(result.startedAt());
        assertNotNull(result.completedAt());
        assertFalse(result.duration().isNegative());
    }

    // ─── Null Safety ───

    @Test
    void should_throw_on_null_definition() {
        assertThrows(NullPointerException.class,
                () -> engine.execute(null, context, Map.of()));
    }

    @Test
    void should_throw_on_null_context() {
        WorkflowDefinition def = WorkflowDefinition.builder("wf")
                .step(successStep("s", Map.of()))
                .build();
        assertThrows(NullPointerException.class,
                () -> engine.execute(def, null, Map.of()));
    }

    @Test
    void should_handle_null_initial_inputs() {
        WorkflowStep step = successStep("step-1", Map.of());
        WorkflowDefinition def = WorkflowDefinition.builder("null-input-wf").step(step).build();

        WorkflowResult result = engine.execute(def, context, null);

        assertTrue(result.isSuccess());
        assertEquals("UNKNOWN", result.ticketKey());
    }

    // ─── Helpers ───

    private WorkflowStep successStep(String id, Map<String, Object> outputs) {
        return new WorkflowStep() {
            @Override
            public String stepId() { return id; }
            @Override
            public String displayName() { return "Step " + id; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.success(id, outputs);
            }
        };
    }

    private WorkflowStep failingStep(String id, String errorMessage) {
        return new WorkflowStep() {
            @Override
            public String stepId() { return id; }
            @Override
            public String displayName() { return "Failing Step " + id; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.failed(id, errorMessage);
            }
        };
    }
}
