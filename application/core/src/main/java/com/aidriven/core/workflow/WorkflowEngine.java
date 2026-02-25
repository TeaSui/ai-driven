package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Executes {@link WorkflowDefinition}s by running each step in sequence.
 *
 * <p>The engine is the runtime counterpart to the declarative {@link WorkflowDefinition}.
 * It handles:
 * <ul>
 *   <li>Step sequencing and state propagation</li>
 *   <li>Retry logic for transient failures</li>
 *   <li>Halt-on-failure vs continue-on-failure semantics</li>
 *   <li>Execution tracking via {@link WorkflowExecution}</li>
 * </ul>
 *
 * <p>This follows the same pattern as {@link com.aidriven.core.agent.AgentOrchestrator}:
 * a central executor that coordinates modular components.
 */
@Slf4j
public class WorkflowEngine {

    private static final int DEFAULT_RETRY_DELAY_MS = 1000;

    /**
     * Executes a workflow definition for the given ticket.
     *
     * @param definition The workflow to execute
     * @param context    The operation context (tenant, user, correlation IDs)
     * @param ticketKey  The Jira ticket key driving this execution
     * @return The completed execution record
     */
    public WorkflowExecution execute(
            WorkflowDefinition definition,
            OperationContext context,
            String ticketKey) {

        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(ticketKey, "ticketKey must not be null");

        String executionId = UUID.randomUUID().toString();
        WorkflowExecution execution = new WorkflowExecution(executionId, definition.getId(), ticketKey);
        WorkflowState state = new WorkflowState(executionId, ticketKey);

        log.info("Starting workflow execution: id={} workflow={} ticket={}",
                executionId, definition.getId(), ticketKey);

        List<WorkflowStep> steps = definition.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            log.info("Executing step [{}/{}]: {} ({})",
                    i + 1, steps.size(), step.stepId(), step.displayName());

            WorkflowStepResult result = executeStepWithRetry(step, context, state);
            execution.addStepResult(result);
            state.mergeOutputs(result);

            if (result.isFailed()) {
                log.error("Step '{}' failed: {}", step.stepId(), result.message());
                if (definition.isHaltOnStepFailure()) {
                    execution.fail("Step '" + step.stepId() + "' failed: " + result.message());
                    log.error("Workflow {} halted after step failure. executionId={}",
                            definition.getId(), executionId);
                    return execution;
                }
            } else if (result.isSkipped()) {
                log.info("Step '{}' skipped: {}", step.stepId(), result.message());
            } else {
                log.info("Step '{}' completed successfully.", step.stepId());
            }
        }

        execution.complete();
        log.info("Workflow execution completed: id={} workflow={} durationMs={}",
                executionId, definition.getId(), execution.getDurationMs());
        return execution;
    }

    /**
     * Executes a single step with retry logic.
     */
    private WorkflowStepResult executeStepWithRetry(
            WorkflowStep step,
            OperationContext context,
            WorkflowState state) {

        int maxAttempts = step.isRetryable() ? step.maxRetries() + 1 : 1;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                WorkflowStepResult result = step.execute(context, state);
                if (attempt > 1) {
                    log.info("Step '{}' succeeded on attempt {}/{}",
                            step.stepId(), attempt, maxAttempts);
                }
                return result;
            } catch (WorkflowStepException e) {
                // Non-retryable failure
                log.error("Step '{}' threw non-retryable exception: {}", step.stepId(), e.getMessage());
                return WorkflowStepResult.failed(step.stepId(), e.getMessage());
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("Step '{}' failed on attempt {}/{}, retrying in {}ms: {}",
                            step.stepId(), attempt, maxAttempts, DEFAULT_RETRY_DELAY_MS, e.getMessage());
                    try {
                        Thread.sleep(DEFAULT_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return WorkflowStepResult.failed(step.stepId(), "Interrupted during retry");
                    }
                } else {
                    log.error("Step '{}' exhausted all {} attempts. Last error: {}",
                            step.stepId(), maxAttempts, e.getMessage());
                }
            }
        }

        String errorMsg = lastException != null ? lastException.getMessage() : "Unknown error";
        return WorkflowStepResult.failed(step.stepId(), errorMsg);
    }
}
