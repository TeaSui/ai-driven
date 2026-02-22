package com.workflow.api

import java.time.Instant
import java.util.UUID

/**
 * Represents a running or completed workflow execution instance.
 */
data class WorkflowExecution(
    val id: ExecutionId,
    val workflowId: WorkflowId,
    val tenantId: TenantId,
    val status: ExecutionStatus,
    val context: ExecutionContext,
    val stepResults: List<StepResult> = emptyList(),
    val startedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val error: String? = null
) {
    fun isTerminal(): Boolean = status in listOf(
        ExecutionStatus.COMPLETED,
        ExecutionStatus.FAILED,
        ExecutionStatus.CANCELLED
    )

    fun withStepResult(result: StepResult): WorkflowExecution =
        copy(stepResults = stepResults + result)

    fun complete(): WorkflowExecution =
        copy(status = ExecutionStatus.COMPLETED, completedAt = Instant.now())

    fun fail(error: String): WorkflowExecution =
        copy(status = ExecutionStatus.FAILED, completedAt = Instant.now(), error = error)
}

@JvmInline
value class ExecutionId(val value: String) {
    companion object {
        fun generate(): ExecutionId = ExecutionId(UUID.randomUUID().toString())
        fun of(value: String): ExecutionId = ExecutionId(value)
    }
}

enum class ExecutionStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, PAUSED
}

/**
 * Mutable context passed between workflow steps.
 */
data class ExecutionContext(
    val triggerData: Map<String, Any> = emptyMap(),
    val variables: MutableMap<String, Any> = mutableMapOf(),
    val metadata: Map<String, String> = emptyMap()
) {
    fun get(key: String): Any? = variables[key] ?: triggerData[key]

    fun set(key: String, value: Any): ExecutionContext {
        variables[key] = value
        return this
    }

    fun merge(data: Map<String, Any>): ExecutionContext {
        variables.putAll(data)
        return this
    }
}

/**
 * Result of a single step execution.
 */
data class StepResult(
    val stepId: StepId,
    val stepName: String,
    val status: StepStatus,
    val output: Map<String, Any> = emptyMap(),
    val error: String? = null,
    val attempt: Int = 1,
    val executedAt: Instant = Instant.now(),
    val durationMs: Long = 0
)

enum class StepStatus {
    SUCCESS, FAILED, SKIPPED, RETRYING
}
