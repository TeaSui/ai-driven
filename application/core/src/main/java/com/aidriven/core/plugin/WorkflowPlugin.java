package com.aidriven.core.plugin;

import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.tenant.TenantConfig;

import java.util.List;

/**
 * Contract for workflow automation plugins.
 *
 * <p>Plugins extend the AI agent's capabilities by providing
 * additional tools for specific business domains (monitoring,
 * messaging, data, etc.).
 *
 * <p>Each plugin:
 * <ul>
 *   <li>Has a unique namespace identifier</li>
 *   <li>Provides one or more {@link ToolProvider} implementations</li>
 *   <li>Can be enabled/disabled per tenant</li>
 *   <li>Is initialized with tenant-specific configuration</li>
 * </ul>
 *
 * <p>Adding a new integration = implement this interface + register in
 * {@link PluginRegistry}. Zero changes to core orchestration.
 */
public interface WorkflowPlugin {

    /**
     * Unique namespace identifier for this plugin.
     * Must match the namespace used in tenant configuration.
     * Example: "monitoring", "messaging", "data", "crm"
     */
    String namespace();

    /**
     * Human-readable name for this plugin.
     */
    String displayName();

    /**
     * Creates tool providers for this plugin, configured for the given tenant.
     *
     * @param tenantConfig The tenant configuration
     * @return List of tool providers to register in the ToolRegistry
     */
    List<ToolProvider> createToolProviders(TenantConfig tenantConfig);

    /**
     * Checks if this plugin supports the given tenant configuration.
     * Allows plugins to declare compatibility requirements.
     *
     * @param tenantConfig The tenant configuration to check
     * @return true if this plugin can be used with the given tenant
     */
    default boolean supportstenant(TenantConfig tenantConfig) {
        return tenantConfig != null && tenantConfig.isActive();
    }

    /**
     * Called when the plugin is registered in the registry.
     * Override for initialization logic (e.g., connection pooling).
     */
    default void onRegister() {
        // Default: no-op
    }

    /**
     * Called when the plugin is deregistered or the system shuts down.
     * Override for cleanup logic (e.g., closing connections).
     */
    default void onDeregister() {
        // Default: no-op
    }
}
