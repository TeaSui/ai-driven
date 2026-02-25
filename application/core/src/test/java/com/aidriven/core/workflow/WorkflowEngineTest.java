package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEngineTest {

    private WorkflowRegistry registry;
    private WorkflowEngine engine;
    private OperationContext context;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        engine = new WorkflowEngine(registry);
        context = OperationContext.builder().tenantId("test-tenant").userId("test-user").build();
    }

    // --- Happy path ---

    @Test
    void should_execute_single_step_workflow_successfully() {
        WorkflowStep step = successStep("step-1", Map.of("result", "done"));
        registry.register(new WorkflowDefinition("test-workflow", "Test", List.of(step)));

        WorkflowResult result = engine.execute("test-workflow", context, Map.of("input", "value"));

        assertTrue(result.isSuccess());
        assertEquals(WorkflowStatus.COMPLETED, result.status());
        assertEquals(1, result.stepResults().size());
        assertEquals("done", result.outputs().get("result"));
    }

    @Test
    void should_propagate_outputs_between_steps() {
        WorkflowStep step1 = successStep("step-1", Map.of("key1", "value1"));
        WorkflowStep step2 = new WorkflowStep() {
            @Override
            public String stepId() { return "step-2"; }
            @Override
            public String description() { return "reads step-1 output"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                String val = wfCtx.requireString("key1");
                return WorkflowStepResult.success(stepId(), Map.of("key2", val + "-processed"));
            }
        };
        registry.register(new WorkflowDefinition("chained", "Chained", List.of(step1, step2)));

        WorkflowResult result = engine.execute("chained", context, Map.of());

        assertTrue(result.isSuccess());
        assertEquals("value1-processed", result.outputs().get("key2"));
    }

    @Test
    void should_execute_all_steps_in_order() {
        List<String> executionOrder = new java.util.ArrayList<>();
        WorkflowStep s1 = trackingStep("s1", executionOrder);
        WorkflowStep s2 = trackingStep("s2", executionOrder);
        WorkflowStep s3 = trackingStep("s3", executionOrder);
        registry.register(new WorkflowDefinition("ordered", "Ordered", List.of(s1, s2, s3)));

        engine.execute("ordered", context, Map.of());

        assertEquals(List.of("s1", "s2", "s3"), executionOrder);
    }

    // --- Failure handling ---

    @Test
    void should_halt_on_mandatory_step_failure_by_default() {
        WorkflowStep step1 = successStep("step-1", Map.of());
        WorkflowStep step2 = failingStep("step-2", "Something went wrong");
        WorkflowStep step3 = successStep("step-3", Map.of());
        registry.register(new WorkflowDefinition("failing", "Failing", List.of(step1, step2, step3)));

        WorkflowResult result = engine.execute("failing", context, Map.of());

        assertTrue(result.isFailed());
        assertEquals(2, result.stepResults().size()); // step3 not executed
        assertEquals("Something went wrong", result.errorMessage());
    }

    @Test
    void should_continue_after_optional_step_failure() {
        WorkflowStep step1 = successStep("step-1", Map.of());
        WorkflowStep optionalStep = optionalFailingStep("optional-step", "Optional failed");
        WorkflowStep step3 = successStep("step-3", Map.of("final", "done"));
        registry.register(new WorkflowDefinition("with-optional", "With optional",
                List.of(step1, optionalStep, step3)));

        WorkflowResult result = engine.execute("with-optional", context, Map.of());

        assertTrue(result.isSuccess());
        assertEquals(3, result.stepResults().size());
        assertEquals("done", result.outputs().get("final"));
    }

    @Test
    void should_continue_on_failure_with_lenient_policy() {
        WorkflowStep step1 = failingStep("step-1", "Failure");
        WorkflowStep step2 = successStep("step-2", Map.of("ok", true));
        WorkflowDefinition def = new WorkflowDefinition(
                "lenient", "Lenient", List.of(step1, step2), WorkflowPolicy.lenient());
        registry.register(def);

        WorkflowResult result = engine.execute("lenient", context, Map.of());

        // With lenient policy (haltOnStepFailure=false), both steps run
        assertEquals(2, result.stepResults().size());
    }

    // --- Registry ---

    @Test
    void should_throw_when_workflow_type_not_registered() {
        assertThrows(WorkflowNotFoundException.class,
                () -> engine.execute("unknown-type", context, Map.of()));
    }

    @Test
    void should_throw_on_duplicate_registration() {
        WorkflowDefinition def = new WorkflowDefinition("dup", "Dup",
                List.of(successStep("s", Map.of())));
        registry.register(def);

        assertThrows(IllegalArgumentException.class, () -> registry.register(def));
    }

    @Test
    void should_allow_replace_via_registerOrReplace() {
        WorkflowStep original = successStep("s", Map.of("v", "original"));
        WorkflowStep replacement = successStep("s", Map.of("v", "replaced"));
        registry.register(new WorkflowDefinition("replaceable", "R", List.of(original)));
        registry.registerOrReplace(new WorkflowDefinition("replaceable", "R", List.of(replacement)));

        WorkflowResult result = engine.execute("replaceable", context, Map.of());
        assertEquals("replaced", result.outputs().get("v"));
    }

    // --- WorkflowContext ---

    @Test
    void should_seed_context_with_initial_inputs() {
        WorkflowStep step = new WorkflowStep() {
            @Override public String stepId() { return "check"; }
            @Override public String description() { return "checks input"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                String ticketKey = wfCtx.requireString(WorkflowContext.KEY_TICKET_KEY);
                return WorkflowStepResult.success(stepId(), Map.of("found", ticketKey));
            }
        };
        registry.register(new WorkflowDefinition("input-test", "Input", List.of(step)));

        WorkflowResult result = engine.execute("input-test", context,
                Map.of(WorkflowContext.KEY_TICKET_KEY, "PROJ-123"));

        assertTrue(result.isSuccess());
        assertEquals("PROJ-123", result.outputs().get("found"));
    }

    @Test
    void should_throw_when_required_context_key_missing() {
        WorkflowStep step = new WorkflowStep() {
            @Override public String stepId() { return "check"; }
            @Override public String description() { return "requires key"; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx)
                    throws WorkflowStepException {
                try {
                    wfCtx.requireString("missing-key");
                    return WorkflowStepResult.success(stepId(), Map.of());
                } catch (IllegalStateException e) {
                    throw new WorkflowStepException(stepId(), e.getMessage(), e);
                }
            }
        };
        registry.register(new WorkflowDefinition("missing-key-test", "Missing", List.of(step)));

        WorkflowResult result = engine.execute("missing-key-test", context, Map.of());
        assertTrue(result.isFailed());
    }

    // --- WorkflowResult ---

    @Test
    void should_count_successful_and_failed_steps() {
        WorkflowStep s1 = successStep("s1", Map.of());
        WorkflowStep s2 = optionalFailingStep("s2", "fail");
        WorkflowStep s3 = successStep("s3", Map.of());
        registry.register(new WorkflowDefinition("counting", "Counting", List.of(s1, s2, s3)));

        WorkflowResult result = engine.execute("counting", context, Map.of());

        assertEquals(2, result.successfulSteps());
        assertEquals(1, result.failedSteps());
    }

    @Test
    void should_record_duration() {
        registry.register(new WorkflowDefinition("timed", "Timed",
                List.of(successStep("s", Map.of()))));

        WorkflowResult result = engine.execute("timed", context, Map.of());

        assertNotNull(result.startedAt());
        assertNotNull(result.completedAt());
        assertFalse(result.duration().isNegative());
    }

    // --- Helpers ---

    private WorkflowStep successStep(String id, Map<String, Object> outputs) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String description() { return "success step " + id; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.success(id, outputs);
            }
        };
    }

    private WorkflowStep failingStep(String id, String message) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String description() { return "failing step " + id; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx)
                    throws WorkflowStepException {
                throw new WorkflowStepException(id, message);
            }
        };
    }

    private WorkflowStep optionalFailingStep(String id, String message) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String description() { return "optional failing step " + id; }
            @Override public boolean isOptional() { return true; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx)
                    throws WorkflowStepException {
                throw new WorkflowStepException(id, message);
            }
        };
    }

    private WorkflowStep trackingStep(String id, List<String> order) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String description() { return "tracking step " + id; }
            @Override
            public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                order.add(id);
                return WorkflowStepResult.success(id, Map.of());
            }
        };
    }
}
