package com.workflow.plugin

import com.workflow.api.*
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Base class providing common utilities for plugin implementations.
 * Handles timing, error wrapping, and context resolution.
 */
abstract class AbstractWorkflowPlugin : WorkflowPlugin {

    protected val log = LoggerFactory.getLogger(this::class.java)

    override fun execute(
        action: String,
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: com.workflow.tenant.TenantConfiguration
    ): StepResult {
        if (!supportsAction(action)) {
            return failResult(step, "Action '$action' not supported by plugin '$pluginId'")
        }

        val startTime = Instant.now()
        return try {
            log.debug("Executing action '{}' in plugin '{}' for step '{}'", action, pluginId, step.name)
            val result = doExecute(action, step, context, tenantConfig)
            log.debug("Action '{}' completed with status {}", action, result.status)
            result.copy(durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli())
        } catch (ex: Exception) {
            log.error("Plugin '{}' action '{}' failed: {}", pluginId, action, ex.message, ex)
            failResult(step, ex.message ?: "Unknown error",
                durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli())
        }
    }

    protected abstract fun doExecute(
        action: String,
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: com.workflow.tenant.TenantConfiguration
    ): StepResult

    protected fun successResult(
        step: StepDefinition,
        output: Map<String, Any> = emptyMap(),
        durationMs: Long = 0
    ): StepResult = StepResult(
        stepId = step.id,
        stepName = step.name,
        status = StepStatus.SUCCESS,
        output = output,
        durationMs = durationMs
    )

    protected fun failResult(
        step: StepDefinition,
        error: String,
        durationMs: Long = 0
    ): StepResult = StepResult(
        stepId = step.id,
        stepName = step.name,
        status = StepStatus.FAILED,
        error = error,
        durationMs = durationMs
    )

    protected fun skippedResult(step: StepDefinition): StepResult = StepResult(
        stepId = step.id,
        stepName = step.name,
        status = StepStatus.SKIPPED
    )

    protected fun getRequiredConfig(step: StepDefinition, key: String): String {
        return step.config[key]?.toString()
            ?: throw IllegalArgumentException("Required config '$key' missing in step '${step.name}'")
    }

    protected fun getOptionalConfig(step: StepDefinition, key: String, default: String = ""): String {
        return step.config[key]?.toString() ?: default
    }

    protected fun resolveTemplate(template: String, context: ExecutionContext): String {
        var result = template
        // Simple {{variable}} template resolution
        val pattern = Regex("\\{\\{(\\w+)\\}\\}")
        pattern.findAll(template).forEach { match ->
            val key = match.groupValues[1]
            val value = context.get(key)?.toString() ?: ""
            result = result.replace(match.value, value)
        }
        return result
    }
}
