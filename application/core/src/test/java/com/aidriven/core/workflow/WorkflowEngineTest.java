package com.aidriven.core.workflow;

import com.aidriven.core.workflow.steps.ConditionalStep;
import com.aidriven.core.workflow.steps.NoOpStep;
import com.aidriven.spi.model.OperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowEngineTest {

    private WorkflowEngine engine;
    private OperationContext context;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngine();
        context = OperationContext.builder().tenantId("test-tenant").userId("test-user").build();
    }

    // ─── Registration ─────────────────────────────────────────────────────────

    @Test
    void register_workflow_succeeds() {
        WorkflowDefinition def = WorkflowDefinition.builder("test-wf")
                .step(new NoOpStep("step1"))
                .build();
        engine.register(def);
        assertThat(engine.getRegisteredWorkflows()).contains("test-wf");
    }

    @Test
    void register_duplicate_workflow_throws() {
        WorkflowDefinition def = WorkflowDefinition.builder("test-wf")
                .step(new NoOpStep("step1"))
                .build();
        engine.register(def);
        assertThatThrownBy(() -> engine.register(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void registerOrReplace_overwrites_existing() {
        WorkflowDefinition def1 = WorkflowDefinition.builder("test-wf")
                .step(new NoOpStep("step1"))
                .build();
        WorkflowDefinition def2 = WorkflowDefinition.builder("test-wf")
                .step(new NoOpStep("step1"))
                .step(new NoOpStep("step2"))
                .build();
        engine.register(def1);
        engine.registerOrReplace(def2);
        assertThat(engine.getDefinition("test-wf").getStepOrder()).hasSize(2);
    }

    // ─── Execution ────────────────────────────────────────────────────────────

    @Test
    void execute_single_step_workflow_completes() {
        WorkflowDefinition def = WorkflowDefinition.builder("simple")
                .step(new NoOpStep("only-step"))
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("simple", "tenant1", Map.of());
        WorkflowExecution result = engine.execute(context, execution);

        assertThat(result.getStatus()).isEqualTo(WorkflowExecution.ExecutionStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    void execute_multi_step_workflow_runs_all_steps_in_order() {
        List<String> executionOrder = new java.util.ArrayList<>();

        WorkflowStep step1 = new WorkflowStep() {
            @Override public String stepId() { return "step1"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                executionOrder.add("step1");
                return WorkflowStepResult.success("done", Map.of("key", "value1"));
            }
        };
        WorkflowStep step2 = new WorkflowStep() {
            @Override public String stepId() { return "step2"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                executionOrder.add("step2");
                // Verify step1 output is accessible
                assertThat(exec.getStepOutputValue("step1", "key")).contains("value1");
                return WorkflowStepResult.success();
            }
        };
        WorkflowStep step3 = new WorkflowStep() {
            @Override public String stepId() { return "step3"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                executionOrder.add("step3");
                return WorkflowStepResult.success();
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("multi-step")
                .step(step1).step(step2).step(step3)
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("multi-step", "tenant1", Map.of());
        engine.execute(context, execution);

        assertThat(executionOrder).containsExactly("step1", "step2", "step3");
    }

    @Test
    void execute_step_failure_halts_workflow() {
        WorkflowStep failStep = new WorkflowStep() {
            @Override public String stepId() { return "fail-step"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                return WorkflowStepResult.fatalFailure("Something went wrong");
            }
        };
        WorkflowStep nextStep = new WorkflowStep() {
            @Override public String stepId() { return "next-step"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                throw new AssertionError("Should not be reached");
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("fail-wf")
                .step(failStep).step(nextStep)
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("fail-wf", "tenant1", Map.of());
        WorkflowExecution result = engine.execute(context, execution);

        assertThat(result.getStatus()).isEqualTo(WorkflowExecution.ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("Something went wrong");
    }

    @Test
    void execute_waiting_step_pauses_workflow() {
        WorkflowStep waitStep = new WorkflowStep() {
            @Override public String stepId() { return "wait-step"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                return WorkflowStepResult.waiting("Awaiting approval");
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("wait-wf")
                .step(waitStep)
                .step(new NoOpStep("after-wait"))
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("wait-wf", "tenant1", Map.of());
        WorkflowExecution result = engine.execute(context, execution);

        assertThat(result.getStatus()).isEqualTo(WorkflowExecution.ExecutionStatus.WAITING);
        assertThat(result.getCurrentStepId()).isEqualTo("wait-step");
    }

    @Test
    void execute_step_exception_marks_workflow_failed() {
        WorkflowStep throwingStep = new WorkflowStep() {
            @Override public String stepId() { return "throw-step"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec)
                    throws WorkflowStepException {
                throw WorkflowStepException.fatal("throw-step", "Fatal error");
            }
            @Override public boolean isRetryable() { return false; }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("throw-wf")
                .step(throwingStep)
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("throw-wf", "tenant1", Map.of());
        WorkflowExecution result = engine.execute(context, execution);

        assertThat(result.getStatus()).isEqualTo(WorkflowExecution.ExecutionStatus.FAILED);
    }

    @Test
    void execute_retryable_step_retries_on_exception() {
        AtomicInteger attempts = new AtomicInteger(0);

        WorkflowStep retryStep = new WorkflowStep() {
            @Override public String stepId() { return "retry-step"; }
            @Override public boolean isRetryable() { return true; }
            @Override public int maxRetries() { return 3; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec)
                    throws WorkflowStepException {
                int attempt = attempts.incrementAndGet();
                if (attempt < 3) {
                    throw WorkflowStepException.retryable("retry-step", "Transient error attempt " + attempt);
                }
                return WorkflowStepResult.success("Succeeded on attempt " + attempt);
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("retry-wf")
                .step(retryStep)
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("retry-wf", "tenant1", Map.of());
        WorkflowExecution result = engine.execute(context, execution);

        assertThat(result.getStatus()).isEqualTo(WorkflowExecution.ExecutionStatus.COMPLETED);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void execute_step_output_is_accessible_to_subsequent_steps() {
        WorkflowStep producer = new WorkflowStep() {
            @Override public String stepId() { return "producer"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                return WorkflowStepResult.success(Map.of("prUrl", "https://github.com/pr/1"));
            }
        };
        WorkflowStep consumer = new WorkflowStep() {
            @Override public String stepId() { return "consumer"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                String prUrl = exec.<String>getStepOutputValue("producer", "prUrl").orElse("missing");
                exec.setVariable("consumedPrUrl", prUrl);
                return WorkflowStepResult.success();
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("data-flow")
                .step(producer).step(consumer)
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("data-flow", "tenant1", Map.of());
        engine.execute(context, execution);

        assertThat(execution.<String>getVariable("consumedPrUrl")).contains("https://github.com/pr/1");
    }

    // ─── Conditional routing ─────────────────────────────────────────────────

    @Test
    void conditional_step_routes_to_true_branch() {
        List<String> visited = new java.util.ArrayList<>();

        WorkflowDefinition def = WorkflowDefinition.builder("conditional-wf")
                .step(new ConditionalStep(
                        "check",
                        exec -> true,
                        "true-branch",
                        "false-branch"))
                .step(new WorkflowStep() {
                    @Override public String stepId() { return "true-branch"; }
                    @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                        visited.add("true-branch");
                        return WorkflowStepResult.success();
                    }
                })
                .step(new WorkflowStep() {
                    @Override public String stepId() { return "false-branch"; }
                    @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                        visited.add("false-branch");
                        return WorkflowStepResult.success();
                    }
                })
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("conditional-wf", "tenant1", Map.of());
        engine.execute(context, execution);

        // After routing to true-branch, the engine continues sequentially to false-branch
        // (since both are in stepOrder). The conditional step only controls the NEXT step.
        assertThat(visited).contains("true-branch");
    }

    // ─── Input access ─────────────────────────────────────────────────────────

    @Test
    void execution_input_is_accessible_to_steps() {
        WorkflowStep step = new WorkflowStep() {
            @Override public String stepId() { return "input-reader"; }
            @Override public WorkflowStepResult execute(OperationContext ctx, WorkflowExecution exec) {
                String ticketKey = exec.<String>getInput("ticketKey").orElse("missing");
                exec.setVariable("readTicketKey", ticketKey);
                return WorkflowStepResult.success();
            }
        };

        WorkflowDefinition def = WorkflowDefinition.builder("input-wf")
                .step(step)
                .build();
        engine.register(def);

        WorkflowExecution execution = engine.createExecution("input-wf", "tenant1",
                Map.of("ticketKey", "PROJ-123"));
        engine.execute(context, execution);

        assertThat(execution.<String>getVariable("readTicketKey")).contains("PROJ-123");
    }

    // ─── Unknown workflow ─────────────────────────────────────────────────────

    @Test
    void execute_unknown_workflow_marks_failed() {
        WorkflowExecution execution = WorkflowExecution.builder()
                .executionId("exec-1")
                .workflowId("unknown-wf")
                .tenantId("tenant1")
                .input(Map.of())
                .build();

        WorkflowExecution result = engine.execute(context, execution);

        assertThat(result.getStatus()).isEqualTo(WorkflowExecution.ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("not found");
    }
}
