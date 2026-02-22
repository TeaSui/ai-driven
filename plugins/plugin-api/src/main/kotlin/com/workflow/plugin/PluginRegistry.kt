package com.workflow.plugin

import com.workflow.api.TenantId
import com.workflow.tenant.TenantConfiguration
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for workflow plugins.
 * Supports global plugins and tenant-specific plugin overrides.
 */
interface PluginRegistry {
    fun register(plugin: WorkflowPlugin)
    fun getPlugin(pluginId: String, tenantId: TenantId): WorkflowPlugin
    fun findPlugin(pluginId: String, tenantId: TenantId): WorkflowPlugin?
    fun getEnabledPlugins(tenantConfig: TenantConfiguration): List<WorkflowPlugin>
    fun getAllPlugins(): List<WorkflowPlugin>
}

/**
 * Default plugin registry with tenant-aware plugin resolution.
 * Tenant-specific plugins take precedence over global plugins.
 */
class DefaultPluginRegistry : PluginRegistry {

    private val log = LoggerFactory.getLogger(DefaultPluginRegistry::class.java)

    // Global plugins available to all tenants
    private val globalPlugins = ConcurrentHashMap<String, WorkflowPlugin>()

    // Tenant-specific plugin overrides
    private val tenantPlugins = ConcurrentHashMap<String, ConcurrentHashMap<String, WorkflowPlugin>>()

    override fun register(plugin: WorkflowPlugin) {
        globalPlugins[plugin.pluginId] = plugin
        log.info("Registered global plugin: {} v{}", plugin.pluginId, plugin.version)
    }

    fun registerForTenant(tenantId: TenantId, plugin: WorkflowPlugin) {
        tenantPlugins
            .getOrPut(tenantId.value) { ConcurrentHashMap() }[plugin.pluginId] = plugin
        log.info("Registered tenant plugin: {} for tenant {}", plugin.pluginId, tenantId.value)
    }

    override fun getPlugin(pluginId: String, tenantId: TenantId): WorkflowPlugin =
        findPlugin(pluginId, tenantId)
            ?: throw PluginNotFoundException(pluginId, tenantId)

    override fun findPlugin(pluginId: String, tenantId: TenantId): WorkflowPlugin? {
        // Tenant-specific plugin takes precedence
        val tenantPlugin = tenantPlugins[tenantId.value]?.get(pluginId)
        return tenantPlugin ?: globalPlugins[pluginId]
    }

    override fun getEnabledPlugins(tenantConfig: TenantConfiguration): List<WorkflowPlugin> {
        val tenantSpecific = tenantPlugins[tenantConfig.tenantId.value]?.values?.toList() ?: emptyList()
        val global = globalPlugins.values.filter { tenantConfig.isPluginEnabled(it.pluginId) }

        // Merge, tenant-specific overrides global
        val merged = (tenantSpecific + global)
            .distinctBy { it.pluginId }
        return merged
    }

    override fun getAllPlugins(): List<WorkflowPlugin> = globalPlugins.values.toList()
}

class PluginNotFoundException(pluginId: String, tenantId: TenantId) :
    RuntimeException("Plugin '$pluginId' not found or not enabled for tenant '${tenantId.value}'")
