package com.workflow.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * Represents a complete workflow definition with steps and triggers.
 * Immutable value object used across modules.
 */
data class WorkflowDefinition(
    val id: WorkflowId,
    val tenantId: TenantId,
    val name: String,
    val description: String = "",
    val trigger: TriggerDefinition,
    val steps: List<StepDefinition>,
    val version: Int = 1,
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(name.isNotBlank()) { "Workflow name must not be blank" }
        require(steps.isNotEmpty()) { "Workflow must have at least one step" }
    }
}

@JvmInline
value class WorkflowId(val value: String) {
    companion object {
        fun generate(): WorkflowId = WorkflowId(UUID.randomUUID().toString())
        fun of(value: String): WorkflowId = WorkflowId(value)
    }
}

@JvmInline
value class TenantId(val value: String) {
    companion object {
        fun of(value: String): TenantId = TenantId(value)
    }
}

@JvmInline
value class StepId(val value: String) {
    companion object {
        fun generate(): StepId = StepId(UUID.randomUUID().toString())
        fun of(value: String): StepId = StepId(value)
    }
}

/**
 * Defines a single step in a workflow.
 */
data class StepDefinition(
    val id: StepId,
    val name: String,
    val pluginId: String,
    val actionId: String,
    val config: Map<String, Any> = emptyMap(),
    val conditions: List<ConditionDefinition> = emptyList(),
    val onSuccess: StepId? = null,
    val onFailure: StepId? = null,
    val retryPolicy: RetryPolicy = RetryPolicy.noRetry()
)

/**
 * Defines a trigger that starts a workflow.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed class TriggerDefinition {
    abstract val type: String
}

data class WebhookTrigger(
    val path: String,
    val method: String = "POST",
    val secretHeader: String? = null
) : TriggerDefinition() {
    override val type: String = "WEBHOOK"
}

data class ScheduleTrigger(
    val cronExpression: String,
    val timezone: String = "UTC"
) : TriggerDefinition() {
    override val type: String = "SCHEDULE"
}

data class EventTrigger(
    val eventType: String,
    val filter: Map<String, Any> = emptyMap()
) : TriggerDefinition() {
    override val type: String = "EVENT"
}

/**
 * Defines a condition for conditional step execution.
 */
data class ConditionDefinition(
    val field: String,
    val operator: ConditionOperator,
    val value: Any
)

enum class ConditionOperator {
    EQUALS, NOT_EQUALS, CONTAINS, GREATER_THAN, LESS_THAN, IS_NULL, IS_NOT_NULL
}

/**
 * Retry policy for workflow steps.
 */
data class RetryPolicy(
    val maxAttempts: Int,
    val backoffMs: Long,
    val backoffMultiplier: Double = 2.0
) {
    companion object {
        fun noRetry() = RetryPolicy(maxAttempts = 1, backoffMs = 0)
        fun withRetry(maxAttempts: Int, backoffMs: Long) = RetryPolicy(maxAttempts, backoffMs)
    }
}
