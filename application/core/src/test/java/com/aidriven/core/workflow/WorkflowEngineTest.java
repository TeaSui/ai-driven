package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEngineTest {

    private WorkflowRegistry registry;
    private WorkflowEngine engine;
    private OperationContext operationContext;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        engine = new WorkflowEngine(registry);
        operationContext = OperationContext.builder().tenantId("test-tenant").userId("test-user").build();
    }

    // --- Helpers ---

    private WorkflowStep successStep(String id) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String displayName() { return "Step " + id; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.success(id, Map.of(id + ".result", "ok"), "Done");
            }
        };
    }

    private WorkflowStep failingStep(String id, boolean required) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String displayName() { return "Failing Step " + id; }
            @Override public boolean isRequired() { return required; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.failed(id, "Simulated failure");
            }
        };
    }

    private WorkflowStep idempotentStep(String id) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String displayName() { return "Idempotent Step " + id; }
            @Override public boolean isIdempotent() { return true; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                return WorkflowStepResult.success(id, Map.of(id + ".result", "computed"), "Computed");
            }
        };
    }

    private WorkflowStep throwingStep(String id) {
        return new WorkflowStep() {
            @Override public String stepId() { return id; }
            @Override public String displayName() { return "Throwing Step " + id; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx)
                    throws WorkflowStepException {
                throw new WorkflowStepException(id, "Unexpected error");
            }
        };
    }

    // --- Tests ---

    @Nested
    class SuccessfulExecution {

        @Test
        void single_step_workflow_succeeds() {
            WorkflowDefinition def = WorkflowDefinition.builder("test-wf")
                    .step(successStep("step1"))
                    .build();
            registry.register(def);

            WorkflowContext ctx = new WorkflowContext("run-1", "PROJ-1");
            WorkflowResult result = engine.execute("test-wf", operationContext, ctx);

            assertTrue(result.isSuccess());
            assertEquals(1, result.stepResults().size());
            assertTrue(result.stepResults().get(0).isSuccess());
            assertEquals("ok", result.outputs().get("step1.result"));
        }

        @Test
        void multi_step_workflow_runs_all_steps_in_order() {
            WorkflowDefinition def = WorkflowDefinition.builder("multi-wf")
                    .step(successStep("step1"))
                    .step(successStep("step2"))
                    .step(successStep("step3"))
                    .build();
            registry.register(def);

            WorkflowContext ctx = new WorkflowContext("run-2", "PROJ-2");
            WorkflowResult result = engine.execute("multi-wf", operationContext, ctx);

            assertTrue(result.isSuccess());
            assertEquals(3, result.stepResults().size());
            assertEquals("step1", result.stepResults().get(0).stepId());
            assertEquals("step2", result.stepResults().get(1).stepId());
            assertEquals("step3", result.stepResults().get(2).stepId());
        }

        @Test
        void step_outputs_are_available_to_subsequent_steps() {
            WorkflowStep writerStep = new WorkflowStep() {
                @Override public String stepId() { return "writer"; }
                @Override public String displayName() { return "Writer"; }
                @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                    return WorkflowStepResult.success("writer", Map.of("shared.value", "hello"), "Written");
                }
            };

            WorkflowStep readerStep = new WorkflowStep() {
                @Override public String stepId() { return "reader"; }
                @Override public String displayName() { return "Reader"; }
                @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowContext wfCtx) {
                    String value = wfCtx.getRequired("shared.value");
                    return WorkflowStepResult.success("reader", Map.of("read.value", value), "Read: " + value);
                }
            };

            WorkflowDefinition def = WorkflowDefinition.builder("pipeline")
                    .step(writerStep)
                    .step(readerStep)
                    .build();
            registry.register(def);

            WorkflowContext ctx = new WorkflowContext("run-3", "PROJ-3");
            WorkflowResult result = engine.execute("pipeline", operationContext, ctx);

            assertTrue(result.isSuccess());
            assertEquals("hello", result.outputs().get("read.value"));
        }
    }

    @Nested
    class FailureHandling {

        @Test
        void required_step_failure_aborts_workflow() {
            WorkflowDefinition def = WorkflowDefinition.builder("abort-wf")
                    .step(successStep("step1"))
                    .step(failingStep("step2", true))
                    .step(successStep("step3"))
                    .build();
            registry.register(def);

            WorkflowContext ctx = new WorkflowContext("run-4", "PROJ-4");
            WorkflowResult result = engine.execute("abort-wf", operationContext, ctx);

            assertTrue(result.isFailed());
            assertEquals(2, result.stepResults().size(), "step3 should not have run");
            assertNotNull(result.errorMessage());
            assertTrue(result.errorMessage().contains("step2"));
        }

        @Test
        void optional_step_failure_continues_workflow() {
            WorkflowDefinition def = WorkflowDefinition.builder("optional-wf")
                    .step(successStep("step1"))
                    .step(failingStep("step2", false))
                    .step(successStep("step3"))
                    .build();
            registry.register(def);

            WorkflowContext ctx = new WorkflowContext("run-5", "PROJ-5");
            WorkflowResult result = engine.execute("optional-wf", operationContext, ctx);

            assertEquals(WorkflowResult.WorkflowStatus.PARTIAL_SUCCESS, result.status());
            assertEquals(3, result.stepResults().size());
            assertTrue(result.stepResults().get(2).isSuccess(), "step3 should have run");
        }

        @Test
        void step_exception_is_caught_and_treated_as_failure() {
            WorkflowDefinition def = WorkflowDefinition.builder("exception-wf")
                    .step(throwingStep("thrower"))
                    .build();
            registry.register(def);

            WorkflowContext ctx = new WorkflowContext("run-6", "PROJ-6");
            WorkflowResult result = engine.execute("exception-wf", operationContext, ctx);

            assertTrue(result.isFailed());
            assertTrue(result.stepResults().get(0).isFailed());
        }
    }

    @Nested
    class IdempotencyHandling {

        @Test
        void idempotent_step_is_skipped_when_output_exists() {
            WorkflowDefinition def = WorkflowDefinition.builder("idem-wf")
                    .step(idempotentStep("idem-step"))
                    .build();
            registry.register(def);

            // Pre-populate the "done" marker
            WorkflowContext ctx = new WorkflowContext("run-7", "PROJ-7",
                    Map.of("idem-step.done", true));
            WorkflowResult result = engine.execute("idem-wf", operationContext, ctx);

            assertTrue(result.isSuccess());
            assertTrue(result.stepResults().get(0).isSkipped());
        }

        @Test
        void idempotent_step_runs_when_output_absent() {
            WorkflowDefinition def = WorkflowDefinition.builder("idem-wf-2")
                    .step(idempotentStep("idem-step"))
                    .build();
            registry.register(def);

            WorkflowContext ctx = new WorkflowContext("run-8", "PROJ-8");
            WorkflowResult result = engine.execute("idem-wf-2", operationContext, ctx);

            assertTrue(result.isSuccess());
            assertTrue(result.stepResults().get(0).isSuccess());
            assertEquals("computed", result.outputs().get("idem-step.result"));
        }
    }

    @Nested
    class RegistryLookup {

        @Test
        void throws_when_workflow_not_registered() {
            WorkflowContext ctx = new WorkflowContext("run-9", "PROJ-9");
            assertThrows(IllegalArgumentException.class,
                    () -> engine.execute("nonexistent-wf", operationContext, ctx));
        }

        @Test
        void execute_with_definition_directly_bypasses_registry() {
            WorkflowDefinition def = WorkflowDefinition.builder("direct-wf")
                    .step(successStep("s1"))
                    .build();
            // NOT registered in registry

            WorkflowContext ctx = new WorkflowContext("run-10", "PROJ-10");
            WorkflowResult result = engine.execute(def, operationContext, ctx);

            assertTrue(result.isSuccess());
        }
    }

    @Nested
    class WorkflowContextTest {

        @Test
        void context_set_and_get() {
            WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");
            ctx.set("key", "value");
            assertEquals("value", ctx.get("key"));
        }

        @Test
        void context_getRequired_throws_when_missing() {
            WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");
            assertThrows(IllegalStateException.class, () -> ctx.getRequired("missing"));
        }

        @Test
        void context_getOptional_returns_empty_when_missing() {
            WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1");
            assertTrue(ctx.getOptional("missing").isEmpty());
        }

        @Test
        void context_snapshot_is_unmodifiable() {
            WorkflowContext ctx = new WorkflowContext("wf-1", "PROJ-1",
                    Map.of("k", "v"));
            Map<String, Object> snapshot = ctx.snapshot();
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.put("new", "value"));
        }
    }

    @Nested
    class WorkflowRegistryTest {

        @Test
        void register_and_find_workflow() {
            WorkflowDefinition def = WorkflowDefinition.builder("reg-wf")
                    .step(successStep("s1"))
                    .build();
            registry.register(def);

            assertTrue(registry.find("reg-wf").isPresent());
            assertEquals("reg-wf", registry.find("reg-wf").get().workflowId());
        }

        @Test
        void find_returns_empty_for_unknown_id() {
            assertTrue(registry.find("unknown").isEmpty());
        }

        @Test
        void register_duplicate_throws() {
            WorkflowDefinition def = WorkflowDefinition.builder("dup-wf")
                    .step(successStep("s1"))
                    .build();
            registry.register(def);
            assertThrows(IllegalArgumentException.class, () -> registry.register(def));
        }

        @Test
        void registered_workflow_ids_returns_all() {
            registry.register(WorkflowDefinition.builder("wf-a").step(successStep("s1")).build());
            registry.register(WorkflowDefinition.builder("wf-b").step(successStep("s1")).build());

            assertTrue(registry.registeredWorkflowIds().contains("wf-a"));
            assertTrue(registry.registeredWorkflowIds().contains("wf-b"));
            assertEquals(2, registry.size());
        }
    }

    @Nested
    class WorkflowDefinitionBuilderTest {

        @Test
        void builder_requires_at_least_one_step() {
            assertThrows(IllegalStateException.class,
                    () -> WorkflowDefinition.builder("empty-wf").build());
        }

        @Test
        void builder_sets_display_name() {
            WorkflowDefinition def = WorkflowDefinition.builder("named-wf")
                    .displayName("My Named Workflow")
                    .step(successStep("s1"))
                    .build();
            assertEquals("My Named Workflow", def.displayName());
        }

        @Test
        void builder_defaults_display_name_to_workflow_id() {
            WorkflowDefinition def = WorkflowDefinition.builder("auto-name")
                    .step(successStep("s1"))
                    .build();
            assertEquals("auto-name", def.displayName());
        }

        @Test
        void steps_list_is_unmodifiable() {
            WorkflowDefinition def = WorkflowDefinition.builder("immutable-wf")
                    .step(successStep("s1"))
                    .build();
            assertThrows(UnsupportedOperationException.class,
                    () -> def.steps().add(successStep("s2")));
        }
    }
}
