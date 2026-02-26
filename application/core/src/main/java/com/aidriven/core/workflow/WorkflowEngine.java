package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core engine that executes workflow definitions by resolving and running steps sequentially.
 *
 * <p>Execution model:
 * <ol>
 *   <li>Resolve the {@link WorkflowDefinition} by ID</li>
 *   <li>For each step ID in the definition, resolve the {@link WorkflowStep} from the registry</li>
 *   <li>Execute each step, merging outputs into the shared {@link WorkflowContext}</li>
 *   <li>On step failure, abort and return a failure result</li>
 * </ol>
 *
 * <p>This is intentionally simple and synchronous. Async/distributed execution
 * is handled at the infrastructure layer (Step Functions, SQS).
 */
@Slf4j
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowDefinitionRegistry definitionRegistry;
    private final WorkflowStepRegistry stepRegistry;

    /**
     * Executes a workflow by ID.
     *
     * @param workflowId The workflow to execute
     * @param ticketKey  The ticket being processed
     * @param context    Security and tenant context
     * @param inputs     Initial inputs for the workflow context
     * @return The execution result
     */
    public WorkflowExecutionResult execute(
            String workflowId,
            String ticketKey,
            OperationContext context,
            Map<String, Object> inputs) {

        Instant startedAt = Instant.now();
        log.info("Starting workflow execution: workflowId={} ticketKey={}", workflowId, ticketKey);

        WorkflowDefinition definition = definitionRegistry.resolve(workflowId)
                .orElse(null);
        if (definition == null) {
            log.error("Unknown workflow: {}", workflowId);
            return failure(workflowId, ticketKey, null,
                    "Unknown workflow: " + workflowId, List.of(), inputs, startedAt);
        }

        WorkflowContext wfContext = new WorkflowContext(workflowId, ticketKey, inputs);
        List<String> completedSteps = new ArrayList<>();

        for (String stepId : definition.stepIds()) {
            WorkflowStep step = stepRegistry.resolve(stepId).orElse(null);
            if (step == null) {
                log.error("Workflow step not found: stepId={} workflowId={}", stepId, workflowId);
                return failure(workflowId, ticketKey, stepId,
                        "Step not found: " + stepId, completedSteps, wfContext.getAll(), startedAt);
            }

            log.info("Executing step: {} - {}", stepId, step.description());
            WorkflowStepResult result;
            try {
                result = step.execute(context, wfContext);
            } catch (Exception e) {
                log.error("Step threw exception: stepId={} error={}", stepId, e.getMessage(), e);
                return failure(workflowId, ticketKey, stepId,
                        "Step " + stepId + " threw exception: " + e.getMessage(),
                        completedSteps, wfContext.getAll(), startedAt);
            }

            if (result.isFailure()) {
                log.warn("Step failed: stepId={} error={}", stepId, result.errorMessage());
                return failure(workflowId, ticketKey, stepId,
                        result.errorMessage(), completedSteps, wfContext.getAll(), startedAt);
            }

            // Merge step outputs into context
            wfContext.merge(result.outputs());
            completedSteps.add(stepId);

            if (result.skipped()) {
                log.info("Step skipped (idempotent): {}", stepId);
            } else {
                log.info("Step completed: {}", stepId);
            }
        }

        Instant completedAt = Instant.now();
        log.info("Workflow completed successfully: workflowId={} ticketKey={} steps={} duration={}ms",
                workflowId, ticketKey, completedSteps.size(),
                java.time.Duration.between(startedAt, completedAt).toMillis());

        return new WorkflowExecutionResult(
                workflowId, ticketKey, true,
                completedSteps, null, null,
                wfContext.getAll(), startedAt, completedAt);
    }

    private WorkflowExecutionResult failure(
            String workflowId, String ticketKey, String failedStep,
            String errorMessage, List<String> completedSteps,
            Map<String, Object> outputs, Instant startedAt) {
        return new WorkflowExecutionResult(
                workflowId, ticketKey, false,
                completedSteps, failedStep, errorMessage,
                outputs, startedAt, Instant.now());
    }
}
