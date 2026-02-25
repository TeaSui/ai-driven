package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Core execution engine for modular workflow automation.
 *
 * <p>The engine:
 * <ol>
 *   <li>Looks up the {@link WorkflowDefinition} from the {@link WorkflowRegistry}</li>
 *   <li>Creates a {@link WorkflowContext} for the execution</li>
 *   <li>Executes each {@link WorkflowStep} in order, respecting the {@link WorkflowPolicy}</li>
 *   <li>Returns a {@link WorkflowResult} with aggregated step results and outputs</li>
 * </ol>
 *
 * <p>This is the single entry point for all workflow execution — pipeline mode
 * (ai-generate) and agent mode (ai-agent) both route through here, with different
 * registered {@link WorkflowDefinition}s.
 */
@Slf4j
public class WorkflowEngine {

    private final WorkflowRegistry registry;

    public WorkflowEngine(WorkflowRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Executes a workflow by type with the given initial inputs.
     *
     * @param workflowType The type of workflow to execute (must be registered)
     * @param context      Security and tenant context
     * @param inputs       Initial data to seed the {@link WorkflowContext}
     * @return The aggregated result of the workflow execution
     * @throws WorkflowNotFoundException if no definition is registered for the given type
     */
    public WorkflowResult execute(String workflowType, OperationContext context, Map<String, Object> inputs) {
        Objects.requireNonNull(workflowType, "workflowType must not be null");
        Objects.requireNonNull(context, "context must not be null");

        WorkflowDefinition definition = registry.find(workflowType)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "No workflow registered for type: " + workflowType));

        String workflowId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        log.info("Starting workflow: type={} id={} tenant={} steps={}",
                workflowType, workflowId, context.getTenantId(), definition.stepCount());

        WorkflowContext wfContext = WorkflowContext.of(workflowId, workflowType, inputs);
        WorkflowPolicy policy = definition.policy();
        List<WorkflowStepResult> stepResults = new ArrayList<>();

        for (WorkflowStep step : definition.steps()) {
            WorkflowStepResult stepResult = executeStep(step, context, wfContext, policy);
            stepResults.add(stepResult);

            // Propagate step outputs into the shared context
            if (stepResult.isSuccess()) {
                wfContext.putAll(stepResult.outputs());
            }

            // Halt on failure if policy requires it
            if (stepResult.isFailed()) {
                if (policy.haltOnStepFailure() && !step.isOptional()) {
                    log.warn("Workflow {} halting after step {} failed: {}",
                            workflowId, step.stepId(), stepResult.message());
                    return buildResult(workflowId, workflowType, WorkflowStatus.FAILED,
                            stepResults, wfContext, stepResult.message(), startedAt);
                }
                if (!step.isOptional() || !policy.continueOnOptionalFailure()) {
                    log.warn("Workflow {} halting after mandatory step {} failed",
                            workflowId, step.stepId());
                    return buildResult(workflowId, workflowType, WorkflowStatus.FAILED,
                            stepResults, wfContext, stepResult.message(), startedAt);
                }
                log.info("Workflow {} continuing after optional step {} failed",
                        workflowId, step.stepId());
            }
        }

        log.info("Workflow {} completed successfully: type={} steps={}",
                workflowId, workflowType, stepResults.size());
        return buildResult(workflowId, workflowType, WorkflowStatus.COMPLETED,
                stepResults, wfContext, null, startedAt);
    }

    /**
     * Executes a single step with retry support.
     */
    private WorkflowStepResult executeStep(
            WorkflowStep step, OperationContext context,
            WorkflowContext wfContext, WorkflowPolicy policy) {

        int maxAttempts = Math.max(1, step.maxRetries() + 1);
        WorkflowStepException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Executing step: {} (attempt {}/{})", step.stepId(), attempt, maxAttempts);
                WorkflowStepResult result = step.execute(context, wfContext);
                log.info("Step {} completed: status={}", step.stepId(), result.status());
                return result;
            } catch (WorkflowStepException e) {
                lastException = e;
                log.warn("Step {} failed (attempt {}/{}): {}",
                        step.stepId(), attempt, maxAttempts, e.getMessage());
                if (!e.isRetryable() || attempt >= maxAttempts) {
                    break;
                }
                // Brief backoff before retry
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                log.error("Unexpected error in step {}: {}", step.stepId(), e.getMessage(), e);
                return WorkflowStepResult.failed(step.stepId(),
                        "Unexpected error: " + e.getMessage());
            }
        }

        String errorMsg = lastException != null ? lastException.getMessage() : "Step failed";
        return WorkflowStepResult.failed(step.stepId(), errorMsg);
    }

    private WorkflowResult buildResult(
            String workflowId, String workflowType, WorkflowStatus status,
            List<WorkflowStepResult> stepResults, WorkflowContext wfContext,
            String errorMessage, Instant startedAt) {
        return new WorkflowResult(
                workflowId,
                workflowType,
                status,
                stepResults,
                wfContext.snapshot(),
                errorMessage,
                startedAt,
                Instant.now());
    }
}
