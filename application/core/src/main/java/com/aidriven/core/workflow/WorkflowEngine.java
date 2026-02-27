package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The core workflow execution engine.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register and validate {@link WorkflowDefinition}s</li>
 *   <li>Create and manage {@link WorkflowExecution} instances</li>
 *   <li>Execute steps sequentially with retry support</li>
 *   <li>Route between steps based on results and routing rules</li>
 *   <li>Emit structured logs for observability</li>
 * </ul>
 *
 * <p>The engine is stateless — execution state lives in {@link WorkflowExecution}.
 * For durable state across Lambda invocations, persist executions externally.
 */
@Slf4j
public class WorkflowEngine {

    private final Map<String, WorkflowDefinition> registry = new ConcurrentHashMap<>();

    /**
     * Registers a workflow definition.
     *
     * @throws IllegalArgumentException if a workflow with the same ID is already registered
     */
    public void register(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        String id = definition.getWorkflowId();
        if (registry.containsKey(id)) {
            throw new IllegalArgumentException("Workflow '" + id + "' is already registered");
        }
        registry.put(id, definition);
        log.info("Registered workflow '{}' with {} steps", id, definition.getStepOrder().size());
    }

    /**
     * Replaces an existing workflow definition (for hot-reload scenarios).
     */
    public void registerOrReplace(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        registry.put(definition.getWorkflowId(), definition);
        log.info("Registered/replaced workflow '{}'", definition.getWorkflowId());
    }

    /**
     * Creates a new execution for the given workflow.
     *
     * @param workflowId The registered workflow ID
     * @param tenantId   The tenant executing the workflow
     * @param input      Input data for the workflow
     * @return A new {@link WorkflowExecution} in PENDING state
     */
    public WorkflowExecution createExecution(String workflowId, String tenantId, Map<String, Object> input) {
        if (!registry.containsKey(workflowId)) {
            throw new IllegalArgumentException("Workflow '" + workflowId + "' is not registered");
        }
        return WorkflowExecution.builder()
                .executionId(UUID.randomUUID().toString())
                .workflowId(workflowId)
                .tenantId(tenantId)
                .input(input)
                .build();
    }

    /**
     * Executes a workflow from start to finish (or until a WAITING/FAILED state).
     *
     * @param context   The operation context
     * @param execution The execution to run
     * @return The final execution state
     */
    public WorkflowExecution execute(OperationContext context, WorkflowExecution execution) {
        WorkflowDefinition definition = registry.get(execution.getWorkflowId());
        if (definition == null) {
            execution.markFailed("Workflow '" + execution.getWorkflowId() + "' not found");
            return execution;
        }

        log.info("Starting workflow execution: workflowId={} executionId={} tenant={}",
                execution.getWorkflowId(), execution.getExecutionId(), execution.getTenantId());

        String currentStepId = definition.firstStepId();

        while (currentStepId != null) {
            WorkflowStep step = definition.getStep(currentStepId);
            execution.markRunning(currentStepId);

            log.info("Executing step: {} (executionId={})", currentStepId, execution.getExecutionId());

            WorkflowStepResult result = executeStepWithRetry(context, step, execution, definition);

            if (result.isWaiting()) {
                log.info("Workflow paused at step '{}' (executionId={})", currentStepId, execution.getExecutionId());
                execution.markWaiting(currentStepId);
                return execution;
            }

            if (result.isFailure()) {
                String msg = "Step '" + currentStepId + "' failed: " + result.getMessage();
                if (!definition.isContinueOnFailure() || result.getStatus() == WorkflowStepResult.Status.FATAL_FAILURE) {
                    log.error("Workflow failed at step '{}': {}", currentStepId, result.getMessage());
                    execution.markFailed(msg);
                    return execution;
                }
                log.warn("Step '{}' failed but continueOnFailure=true: {}", currentStepId, result.getMessage());
            }

            // Record step output
            if (result.getOutput() != null && !result.getOutput().isEmpty()) {
                execution.recordStepOutput(currentStepId, result.getOutput());
            }

            // Determine next step
            currentStepId = resolveNextStep(definition, currentStepId, result);
        }

        log.info("Workflow completed: workflowId={} executionId={}",
                execution.getWorkflowId(), execution.getExecutionId());
        execution.markCompleted();
        return execution;
    }

    /**
     * Resumes a WAITING execution from the step it was paused at.
     *
     * @param context   The operation context
     * @param execution The execution to resume
     * @return The updated execution state
     */
    public WorkflowExecution resume(OperationContext context, WorkflowExecution execution) {
        if (execution.getStatus() != WorkflowExecution.ExecutionStatus.WAITING) {
            throw new IllegalStateException(
                    "Cannot resume execution '" + execution.getExecutionId() + "' in state " + execution.getStatus());
        }
        log.info("Resuming workflow execution: executionId={} from step={}",
                execution.getExecutionId(), execution.getCurrentStepId());
        return execute(context, execution);
    }

    /**
     * Returns the registered workflow definition, or null if not found.
     */
    public WorkflowDefinition getDefinition(String workflowId) {
        return registry.get(workflowId);
    }

    /**
     * Returns all registered workflow IDs.
     */
    public java.util.Set<String> getRegisteredWorkflows() {
        return java.util.Collections.unmodifiableSet(registry.keySet());
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private WorkflowStepResult executeStepWithRetry(
            OperationContext context,
            WorkflowStep step,
            WorkflowExecution execution,
            WorkflowDefinition definition) {

        int maxAttempts = step.isRetryable() ? step.maxRetries() : 1;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                WorkflowStepResult result = step.execute(context, execution);
                if (result.isSuccess() || result.isWaiting()) {
                    return result;
                }
                if (result.getStatus() == WorkflowStepResult.Status.FATAL_FAILURE) {
                    return result;
                }
                // RETRYABLE_FAILURE — try again
                log.warn("Step '{}' returned retryable failure (attempt {}/{}): {}",
                        step.stepId(), attempt, maxAttempts, result.getMessage());
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
                return result; // return last result after exhausting retries
            } catch (WorkflowStepException e) {
                lastException = e;
                log.warn("Step '{}' threw exception (attempt {}/{}): {}",
                        step.stepId(), attempt, maxAttempts, e.getMessage());
                if (!e.isRetryable() || attempt >= maxAttempts) {
                    return WorkflowStepResult.fatalFailure(e.getMessage());
                }
                sleepBackoff(attempt);
            } catch (Exception e) {
                lastException = e;
                log.error("Step '{}' threw unexpected exception (attempt {}/{}): {}",
                        step.stepId(), attempt, maxAttempts, e.getMessage(), e);
                if (attempt >= maxAttempts) {
                    return WorkflowStepResult.fatalFailure("Unexpected error: " + e.getMessage());
                }
                sleepBackoff(attempt);
            }
        }

        String msg = lastException != null ? lastException.getMessage() : "Unknown error";
        return WorkflowStepResult.fatalFailure("Step '" + step.stepId() + "' exhausted retries: " + msg);
    }

    private String resolveNextStep(WorkflowDefinition definition, String currentStepId, WorkflowStepResult result) {
        // 1. Explicit routing from step result
        if (result.getNextStepId() != null) {
            return result.getNextStepId();
        }
        // 2. Default sequential order
        return definition.nextStepId(currentStepId);
    }

    private void sleepBackoff(int attempt) {
        try {
            long delayMs = 500L * (1L << (attempt - 1)); // 500ms, 1s, 2s, ...
            Thread.sleep(Math.min(delayMs, 5000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
