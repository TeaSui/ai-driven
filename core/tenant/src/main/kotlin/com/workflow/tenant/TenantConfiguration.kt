package com.workflow.tenant

import com.workflow.api.TenantId

/**
 * Tenant-specific configuration that drives workflow behavior.
 * Each tenant can have different plugins, limits, and feature flags.
 */
data class TenantConfiguration(
    val tenantId: TenantId,
    val name: String,
    val plan: TenantPlan,
    val enabledPlugins: Set<String>,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val limits: TenantLimits = TenantLimits.defaults(),
    val integrations: Map<String, IntegrationConfig> = emptyMap(),
    val notificationChannels: List<NotificationChannelConfig> = emptyList()
) {
    fun isPluginEnabled(pluginId: String): Boolean = pluginId in enabledPlugins

    fun isFeatureEnabled(feature: String): Boolean = featureFlags[feature] ?: false

    fun getIntegration(name: String): IntegrationConfig? = integrations[name]
}

enum class TenantPlan {
    FREE, STARTER, PROFESSIONAL, ENTERPRISE;

    fun maxWorkflows(): Int = when (this) {
        FREE -> 3
        STARTER -> 25
        PROFESSIONAL -> 100
        ENTERPRISE -> Int.MAX_VALUE
    }

    fun maxExecutionsPerDay(): Int = when (this) {
        FREE -> 100
        STARTER -> 1_000
        PROFESSIONAL -> 10_000
        ENTERPRISE -> Int.MAX_VALUE
    }
}

data class TenantLimits(
    val maxWorkflows: Int,
    val maxExecutionsPerDay: Int,
    val maxStepsPerWorkflow: Int,
    val maxConcurrentExecutions: Int
) {
    companion object {
        fun defaults() = TenantLimits(
            maxWorkflows = 10,
            maxExecutionsPerDay = 500,
            maxStepsPerWorkflow = 20,
            maxConcurrentExecutions = 5
        )

        fun forPlan(plan: TenantPlan) = TenantLimits(
            maxWorkflows = plan.maxWorkflows(),
            maxExecutionsPerDay = plan.maxExecutionsPerDay(),
            maxStepsPerWorkflow = if (plan == TenantPlan.ENTERPRISE) 100 else 50,
            maxConcurrentExecutions = if (plan == TenantPlan.ENTERPRISE) 50 else 10
        )
    }
}

data class IntegrationConfig(
    val type: String,
    val properties: Map<String, String>
) {
    fun getRequired(key: String): String =
        properties[key] ?: throw IllegalStateException("Required integration property '$key' not found")

    fun getOptional(key: String, default: String = ""): String =
        properties[key] ?: default
}

data class NotificationChannelConfig(
    val channelType: String,
    val name: String,
    val config: Map<String, String>
)
