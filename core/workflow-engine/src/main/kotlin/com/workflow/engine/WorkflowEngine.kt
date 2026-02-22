package com.workflow.engine

import com.workflow.api.*
import com.workflow.plugin.PluginRegistry
import com.workflow.tenant.TenantConfiguration
import com.workflow.tenant.TenantRegistry
import org.slf4j.LoggerFactory

/**
 * Core workflow execution engine.
 * Orchestrates step execution, handles retries, and publishes events.
 */
class WorkflowEngine(
    private val pluginRegistry: PluginRegistry,
    private val tenantRegistry: TenantRegistry,
    private val executionRepository: WorkflowExecutionRepository,
    private val eventPublisher: WorkflowEventPublisher,
    private val conditionEvaluator: ConditionEvaluator = DefaultConditionEvaluator()
) {
    private val log = LoggerFactory.getLogger(WorkflowEngine::class.java)

    /**
     * Trigger a workflow execution.
     * @return The created execution ID
     */
    fun trigger(
        workflow: WorkflowDefinition,
        triggerData: Map<String, Any> = emptyMap()
    ): ExecutionId {
        val tenantConfig = tenantRegistry.get(workflow.tenantId)

        validateWorkflowCanRun(workflow, tenantConfig)

        val executionId = ExecutionId.generate()
        val context = ExecutionContext(triggerData = triggerData)

        val execution = WorkflowExecution(
            id = executionId,
            workflowId = workflow.id,
            tenantId = workflow.tenantId,
            status = ExecutionStatus.PENDING,
            context = context
        )

        executionRepository.save(execution)
        eventPublisher.publish(WorkflowTriggered(
            workflowId = workflow.id,
            executionId = executionId,
            tenantId = workflow.tenantId,
            triggerData = triggerData
        ))

        log.info("Workflow '{}' triggered, executionId={}", workflow.name, executionId.value)
        return executionId
    }

    /**
     * Execute a workflow synchronously.
     * For async execution, use trigger() and process via message queue.
     */
    fun execute(
        workflow: WorkflowDefinition,
        executionId: ExecutionId
    ): WorkflowExecution {
        val tenantConfig = tenantRegistry.get(workflow.tenantId)
        var execution = executionRepository.findById(executionId)
            ?: throw IllegalStateException("Execution not found: ${executionId.value}")

        execution = execution.copy(status = ExecutionStatus.RUNNING)
        executionRepository.save(execution)
        eventPublisher.publish(WorkflowStarted(
            workflowId = workflow.id,
            executionId = executionId,
            tenantId = workflow.tenantId
        ))

        log.info("Starting execution {} for workflow '{}'", executionId.value, workflow.name)

        execution = try {
            executeSteps(workflow, execution, tenantConfig)
        } catch (ex: Exception) {
            log.error("Workflow execution {} failed: {}", executionId.value, ex.message, ex)
            val failed = execution.fail(ex.message ?: "Unexpected error")
            executionRepository.save(failed)
            eventPublisher.publish(WorkflowFailed(
                workflowId = workflow.id,
                executionId = executionId,
                tenantId = workflow.tenantId,
                reason = ex.message ?: "Unexpected error"
            ))
            return failed
        }

        val completed = execution.complete()
        executionRepository.save(completed)
        eventPublisher.publish(WorkflowCompleted(
            workflowId = workflow.id,
            executionId = executionId,
            tenantId = workflow.tenantId,
            stepCount = completed.stepResults.size
        ))

        log.info("Execution {} completed with {} steps", executionId.value, completed.stepResults.size)
        return completed
    }

    private fun executeSteps(
        workflow: WorkflowDefinition,
        initialExecution: WorkflowExecution,
        tenantConfig: TenantConfiguration
    ): WorkflowExecution {
        var execution = initialExecution
        var currentStepId: StepId? = workflow.steps.firstOrNull()?.id

        while (currentStepId != null) {
            val step = workflow.steps.find { it.id == currentStepId }
                ?: break

            // Evaluate conditions
            if (!conditionEvaluator.evaluate(step.conditions, execution.context)) {
                log.debug("Step '{}' skipped due to conditions", step.name)
                val skippedResult = com.workflow.api.StepResult(
                    stepId = step.id,
                    stepName = step.name,
                    status = StepStatus.SKIPPED
                )
                execution = execution.withStepResult(skippedResult)
                currentStepId = step.onSuccess
                continue
            }

            val stepResult = executeStepWithRetry(step, execution.context, tenantConfig)
            execution = execution.withStepResult(stepResult)

            // Merge step output into context
            execution.context.merge(stepResult.output)

            eventPublisher.publish(StepExecuted(
                workflowId = workflow.id,
                executionId = execution.id,
                stepId = step.id,
                tenantId = workflow.tenantId,
                result = stepResult
            ))

            currentStepId = when (stepResult.status) {
                StepStatus.SUCCESS -> step.onSuccess
                StepStatus.FAILED -> {
                    if (step.onFailure != null) {
                        step.onFailure
                    } else {
                        throw WorkflowStepFailedException(step.name, stepResult.error ?: "Step failed")
                    }
                }
                StepStatus.SKIPPED -> step.onSuccess
                StepStatus.RETRYING -> null // Should not happen here
            }
        }

        return execution
    }

    private fun executeStepWithRetry(
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: TenantConfiguration
    ): StepResult {
        val plugin = pluginRegistry.getPlugin(step.pluginId, tenantConfig.tenantId)
        val retryPolicy = step.retryPolicy
        var lastResult: StepResult? = null

        for (attempt in 1..retryPolicy.maxAttempts) {
            lastResult = plugin.execute(step.actionId, step, context, tenantConfig)

            if (lastResult.status == StepStatus.SUCCESS) {
                return lastResult
            }

            if (attempt < retryPolicy.maxAttempts) {
                val backoff = (retryPolicy.backoffMs * Math.pow(retryPolicy.backoffMultiplier, (attempt - 1).toDouble())).toLong()
                log.warn("Step '{}' failed (attempt {}/{}), retrying in {}ms",
                    step.name, attempt, retryPolicy.maxAttempts, backoff)
                Thread.sleep(backoff)
            }
        }

        return lastResult!!
    }

    private fun validateWorkflowCanRun(
        workflow: WorkflowDefinition,
        tenantConfig: TenantConfiguration
    ) {
        require(workflow.enabled) { "Workflow '${workflow.name}' is disabled" }

        workflow.steps.forEach { step ->
            require(tenantConfig.isPluginEnabled(step.pluginId)) {
                "Plugin '${step.pluginId}' is not enabled for tenant '${tenantConfig.tenantId.value}'"
            }
        }
    }
}

class WorkflowStepFailedException(stepName: String, error: String) :
    RuntimeException("Step '$stepName' failed: $error")
