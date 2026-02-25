package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Core engine that executes a {@link WorkflowDefinition} step by step.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Iterate through steps in order</li>
 *   <li>Pass {@link WorkflowContext} between steps</li>
 *   <li>Merge step outputs into the shared context</li>
 *   <li>Handle retries per step configuration</li>
 *   <li>Collect results and produce a {@link WorkflowResult}</li>
 * </ul>
 *
 * <p>The engine is stateless and thread-safe; each call to
 * {@link #execute(WorkflowDefinition, OperationContext, Map)} creates a fresh
 * {@link WorkflowContext}.
 */
@Slf4j
public class WorkflowEngine {

    /**
     * Executes a workflow definition with the given initial inputs.
     *
     * @param definition    The workflow to execute
     * @param context       Security and tenant context
     * @param initialInputs Initial data to seed the workflow context
     * @return The aggregated result of all step executions
     */
    public WorkflowResult execute(
            WorkflowDefinition definition,
            OperationContext context,
            Map<String, Object> initialInputs) {

        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(context, "context must not be null");

        String workflowId = definition.getWorkflowId();
        String ticketKey = initialInputs != null
                ? (String) initialInputs.getOrDefault("ticketKey", "UNKNOWN")
                : "UNKNOWN";

        WorkflowContext wfContext = new WorkflowContext(
                UUID.randomUUID().toString(),
                ticketKey,
                initialInputs);

        Instant startedAt = Instant.now();
        List<WorkflowStepResult> stepResults = new ArrayList<>();
        String errorMessage = null;
        boolean anyFailed = false;

        log.info("Starting workflow '{}' for ticket={} with {} steps",
                workflowId, ticketKey, definition.getSteps().size());

        for (WorkflowStep step : definition.getSteps()) {
            WorkflowStepResult result = executeStepWithRetry(step, context, wfContext);
            stepResults.add(result);

            if (result.isSuccess()) {
                // Merge step outputs into shared context
                wfContext.putAll(result.outputs());
                log.info("Step '{}' succeeded for workflow='{}'", step.stepId(), workflowId);

            } else if (result.isSkipped()) {
                log.info("Step '{}' skipped: {}", step.stepId(), result.message());

            } else {
                // Step failed
                anyFailed = true;
                errorMessage = result.message();
                log.warn("Step '{}' failed for workflow='{}': {}",
                        step.stepId(), workflowId, result.message());

                if (definition.isStopOnFirstFailure() && !step.isOptional()) {
                    log.warn("Stopping workflow '{}' after mandatory step '{}' failed",
                            workflowId, step.stepId());
                    break;
                }
            }
        }

        Instant completedAt = Instant.now();
        WorkflowResult.WorkflowStatus status = determineStatus(stepResults, anyFailed);

        log.info("Workflow '{}' completed with status={} in {}ms",
                workflowId, status, java.time.Duration.between(startedAt, completedAt).toMillis());

        return new WorkflowResult(
                workflowId,
                ticketKey,
                status,
                stepResults,
                wfContext.snapshot(),
                startedAt,
                completedAt,
                errorMessage);
    }

    /**
     * Executes a single step with retry logic.
     */
    private WorkflowStepResult executeStepWithRetry(
            WorkflowStep step,
            OperationContext context,
            WorkflowContext wfContext) {

        int maxAttempts = step.maxRetries() + 1;
        WorkflowStepResult lastResult = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                log.info("Retrying step '{}' (attempt {}/{})", step.stepId(), attempt, maxAttempts);
            }

            try {
                lastResult = step.execute(context, wfContext);
                if (!lastResult.isFailed()) {
                    return lastResult;
                }
                // Step returned a failure result — retry if attempts remain
                if (attempt < maxAttempts) {
                    log.warn("Step '{}' returned failure on attempt {}, will retry: {}",
                            step.stepId(), attempt, lastResult.message());
                }
            } catch (WorkflowStepException e) {
                lastResult = WorkflowStepResult.failed(step.stepId(), e.getMessage());
                if (!e.isRetryable() || attempt >= maxAttempts) {
                    log.error("Step '{}' threw non-retryable exception: {}", step.stepId(), e.getMessage());
                    return lastResult;
                }
                log.warn("Step '{}' threw retryable exception on attempt {}: {}",
                        step.stepId(), attempt, e.getMessage());
            } catch (Exception e) {
                log.error("Step '{}' threw unexpected exception: {}", step.stepId(), e.getMessage(), e);
                return WorkflowStepResult.failed(step.stepId(),
                        "Unexpected error in step '" + step.stepId() + "': " + e.getMessage());
            }
        }

        return lastResult != null ? lastResult
                : WorkflowStepResult.failed(step.stepId(), "Step failed after " + maxAttempts + " attempts");
    }

    private WorkflowResult.WorkflowStatus determineStatus(
            List<WorkflowStepResult> results, boolean anyFailed) {
        if (!anyFailed) {
            return WorkflowResult.WorkflowStatus.SUCCESS;
        }
        long successCount = results.stream().filter(WorkflowStepResult::isSuccess).count();
        return successCount > 0
                ? WorkflowResult.WorkflowStatus.PARTIAL_SUCCESS
                : WorkflowResult.WorkflowStatus.FAILED;
    }
}
