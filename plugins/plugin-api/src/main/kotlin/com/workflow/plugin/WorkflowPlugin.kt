package com.workflow.plugin

import com.workflow.api.ExecutionContext
import com.workflow.api.StepDefinition
import com.workflow.api.StepResult
import com.workflow.api.TenantId
import com.workflow.tenant.TenantConfiguration

/**
 * Core plugin interface that all workflow plugins must implement.
 * Plugins are the extension points for workflow automation actions.
 */
interface WorkflowPlugin {
    /** Unique identifier for this plugin */
    val pluginId: String

    /** Human-readable name */
    val displayName: String

    /** Plugin version */
    val version: String

    /** List of actions this plugin can perform */
    val supportedActions: List<ActionDescriptor>

    /**
     * Execute a specific action within this plugin.
     * @param action The action identifier to execute
     * @param step The step definition containing configuration
     * @param context The current execution context
     * @param tenantConfig Tenant-specific configuration
     * @return Result of the action execution
     */
    fun execute(
        action: String,
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: TenantConfiguration
    ): StepResult

    /**
     * Validate plugin configuration for a given step.
     * Called before workflow execution to catch config errors early.
     */
    fun validate(step: StepDefinition, tenantConfig: TenantConfiguration): ValidationResult {
        return ValidationResult.valid()
    }

    /**
     * Called when plugin is initialized for a tenant.
     * Override to perform tenant-specific setup.
     */
    fun onTenantInit(tenantId: TenantId, tenantConfig: TenantConfiguration) {}

    /**
     * Called when plugin is removed for a tenant.
     */
    fun onTenantDestroy(tenantId: TenantId) {}

    fun supportsAction(actionId: String): Boolean =
        supportedActions.any { it.id == actionId }
}

/**
 * Describes an action that a plugin can perform.
 */
data class ActionDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val requiredConfig: List<ConfigField> = emptyList(),
    val optionalConfig: List<ConfigField> = emptyList()
)

/**
 * Describes a configuration field for a plugin action.
 */
data class ConfigField(
    val name: String,
    val type: FieldType,
    val description: String,
    val defaultValue: Any? = null,
    val sensitive: Boolean = false
)

enum class FieldType {
    STRING, NUMBER, BOOLEAN, EMAIL, URL, SECRET, JSON
}

/**
 * Result of plugin configuration validation.
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList()
) {
    companion object {
        fun valid() = ValidationResult(valid = true)
        fun invalid(vararg errors: String) = ValidationResult(valid = false, errors = errors.toList())
        fun invalid(errors: List<String>) = ValidationResult(valid = false, errors = errors)
    }
}
