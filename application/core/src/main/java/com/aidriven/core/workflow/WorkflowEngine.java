package com.aidriven.core.workflow;

import com.aidriven.spi.model.OperationContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes {@link WorkflowDefinition}s by running their steps in order.
 *
 * <p>The engine is stateless and thread-safe. Each call to
 * {@link #execute(WorkflowDefinition, OperationContext, WorkflowContext)}
 * creates a fresh execution context.
 *
 * <p>Execution semantics:
 * <ul>
 *   <li>Steps run sequentially in definition order.</li>
 *   <li>If a required step fails, execution stops and a FAILED result is returned.</li>
 *   <li>If an optional step fails, execution continues (PARTIAL_SUCCESS).</li>
 *   <li>Idempotent steps are skipped if their output key already exists in context.</li>
 * </ul>
 */
@Slf4j
public class WorkflowEngine {

    private final WorkflowRegistry registry;

    public WorkflowEngine(WorkflowRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Executes a workflow by ID.
     *
     * @param workflowId The registered workflow to execute
     * @param context    Security/tenant context
     * @param wfContext  Initial workflow context (inputs)
     * @return The execution result
     * @throws IllegalArgumentException if the workflow ID is not registered
     */
    public WorkflowResult execute(String workflowId, OperationContext context, WorkflowContext wfContext) {
        WorkflowDefinition definition = registry.find(workflowId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow '" + workflowId + "' is not registered"));
        return execute(definition, context, wfContext);
    }

    /**
     * Executes a workflow definition directly.
     *
     * @param definition The workflow to execute
     * @param context    Security/tenant context
     * @param wfContext  Initial workflow context (inputs)
     * @return The execution result
     */
    public WorkflowResult execute(WorkflowDefinition definition, OperationContext context,
            WorkflowContext wfContext) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(wfContext, "wfContext");

        Instant startedAt = Instant.now();
        List<WorkflowStepResult> stepResults = new ArrayList<>();
        boolean hadOptionalFailure = false;

        log.info("Starting workflow: {} for ticket={} (tenant={})",
                definition.workflowId(), wfContext.getTicketKey(), context.getTenantId());

        for (WorkflowStep step : definition.steps()) {
            // Idempotency check: skip if output already present
            if (step.isIdempotent() && wfContext.has(step.stepId() + ".done")) {
                log.info("Skipping idempotent step: {} (output already present)", step.stepId());
                WorkflowStepResult skipped = WorkflowStepResult.skipped(step.stepId(),
                        "Skipped: output already present");
                stepResults.add(skipped);
                continue;
            }

            log.info("Executing step: {} ({})", step.stepId(), step.displayName());
            WorkflowStepResult result;

            try {
                result = step.execute(context, wfContext);
            } catch (WorkflowStepException e) {
                log.error("Step '{}' threw WorkflowStepException: {}", step.stepId(), e.getMessage(), e);
                result = WorkflowStepResult.failed(step.stepId(), e.getMessage());
            } catch (Exception e) {
                log.error("Step '{}' threw unexpected exception: {}", step.stepId(), e.getMessage(), e);
                result = WorkflowStepResult.failed(step.stepId(),
                        "Unexpected error: " + e.getMessage());
            }

            stepResults.add(result);

            if (result.isSuccess()) {
                // Merge step outputs into workflow context
                wfContext.mergeOutputs(result.outputs());
                // Mark idempotent step as done
                if (step.isIdempotent()) {
                    wfContext.set(step.stepId() + ".done", true);
                }
                log.info("Step '{}' succeeded: {}", step.stepId(), result.message());
            } else if (result.isSkipped()) {
                log.info("Step '{}' skipped: {}", step.stepId(), result.message());
            } else {
                // Step failed
                if (step.isRequired()) {
                    log.error("Required step '{}' failed: {}. Aborting workflow.",
                            step.stepId(), result.message());
                    return WorkflowResult.failed(
                            definition.workflowId(), wfContext.getTicketKey(),
                            stepResults, wfContext.snapshot(),
                            startedAt, Instant.now(),
                            "Required step '" + step.stepId() + "' failed: " + result.message());
                } else {
                    log.warn("Optional step '{}' failed: {}. Continuing.",
                            step.stepId(), result.message());
                    hadOptionalFailure = true;
                }
            }
        }

        Instant completedAt = Instant.now();
        Map<String, Object> finalOutputs = new HashMap<>(wfContext.snapshot());

        if (hadOptionalFailure) {
            log.info("Workflow '{}' completed with partial success for ticket={}",
                    definition.workflowId(), wfContext.getTicketKey());
            return new WorkflowResult(
                    definition.workflowId(), wfContext.getTicketKey(),
                    WorkflowResult.WorkflowStatus.PARTIAL_SUCCESS,
                    List.copyOf(stepResults),
                    Map.copyOf(finalOutputs),
                    startedAt, completedAt, null);
        }

        log.info("Workflow '{}' completed successfully for ticket={}",
                definition.workflowId(), wfContext.getTicketKey());
        return WorkflowResult.success(
                definition.workflowId(), wfContext.getTicketKey(),
                stepResults, finalOutputs, startedAt, completedAt);
    }
}
