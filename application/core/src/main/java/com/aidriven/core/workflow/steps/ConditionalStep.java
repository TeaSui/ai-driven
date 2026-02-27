package com.aidriven.core.workflow.steps;

import com.aidriven.core.workflow.WorkflowExecution;
import com.aidriven.core.workflow.WorkflowStep;
import com.aidriven.core.workflow.WorkflowStepException;
import com.aidriven.core.workflow.WorkflowStepResult;
import com.aidriven.spi.model.OperationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Predicate;

/**
 * A step that evaluates a condition and routes to different next steps.
 *
 * <p>Example usage:
 * <pre>{@code
 * new ConditionalStep(
 *     "check-dry-run",
 *     execution -> Boolean.TRUE.equals(execution.getInput("dryRun").orElse(false)),
 *     "dry-run-summary",   // nextStepId if condition is true
 *     "create-pr"          // nextStepId if condition is false
 * )
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
public class ConditionalStep implements WorkflowStep {

    private final String id;
    private final Predicate<WorkflowExecution> condition;
    private final String trueStepId;
    private final String falseStepId;

    @Override
    public String stepId() {
        return id;
    }

    @Override
    public WorkflowStepResult execute(OperationContext context, WorkflowExecution execution)
            throws WorkflowStepException {
        boolean result = condition.test(execution);
        String nextStep = result ? trueStepId : falseStepId;
        log.info("ConditionalStep '{}': condition={}, routing to '{}'", id, result, nextStep);
        return WorkflowStepResult.routeTo(nextStep);
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
