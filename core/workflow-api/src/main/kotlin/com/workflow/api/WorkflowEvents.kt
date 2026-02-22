package com.workflow.api

import java.time.Instant

/**
 * Domain events for workflow lifecycle.
 * Used for inter-module communication via event bus.
 */
sealed class WorkflowEvent {
    abstract val occurredAt: Instant
    abstract val tenantId: TenantId
}

data class WorkflowTriggered(
    val workflowId: WorkflowId,
    val executionId: ExecutionId,
    override val tenantId: TenantId,
    val triggerData: Map<String, Any>,
    override val occurredAt: Instant = Instant.now()
) : WorkflowEvent()

data class WorkflowStarted(
    val workflowId: WorkflowId,
    val executionId: ExecutionId,
    override val tenantId: TenantId,
    override val occurredAt: Instant = Instant.now()
) : WorkflowEvent()

data class WorkflowCompleted(
    val workflowId: WorkflowId,
    val executionId: ExecutionId,
    override val tenantId: TenantId,
    val stepCount: Int,
    override val occurredAt: Instant = Instant.now()
) : WorkflowEvent()

data class WorkflowFailed(
    val workflowId: WorkflowId,
    val executionId: ExecutionId,
    override val tenantId: TenantId,
    val reason: String,
    override val occurredAt: Instant = Instant.now()
) : WorkflowEvent()

data class StepExecuted(
    val workflowId: WorkflowId,
    val executionId: ExecutionId,
    val stepId: StepId,
    override val tenantId: TenantId,
    val result: StepResult,
    override val occurredAt: Instant = Instant.now()
) : WorkflowEvent()
