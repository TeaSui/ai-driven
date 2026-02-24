package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;

/**
 * Contract for workflow automation plugins.
 *
 * <p>A plugin encapsulates a specific capability (e.g., monitoring, messaging,
 * data access) that can be enabled per-tenant. Plugins are registered in the
 * {@link PluginRegistry} and activated based on tenant configuration.</p>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #initialize(TenantConfig)} — called once per tenant on startup</li>
 *   <li>{@link #isEnabled(TenantConfig)} — checked before each use</li>
 *   <li>{@link #shutdown()} — called on graceful shutdown</li>
 * </ol>
 */
public interface WorkflowPlugin {

    /**
     * Returns the unique plugin identifier (e.g., "monitoring", "messaging").
     * Must match the namespace used in {@link com.aidriven.core.agent.tool.ToolProvider}.
     */
    String pluginId();

    /**
     * Returns a human-readable description of this plugin.
     */
    String description();

    /**
     * Initializes the plugin for a specific tenant.
     * Called once per tenant when the plugin is first activated.
     *
     * @param tenantConfig The tenant configuration
     * @throws Exception if initialization fails
     */
    void initialize(TenantConfig tenantConfig) throws Exception;

    /**
     * Checks if this plugin is enabled for the given tenant.
     *
     * @param tenantConfig The tenant configuration
     * @return true if the plugin should be active for this tenant
     */
    boolean isEnabled(TenantConfig tenantConfig);

    /**
     * Performs cleanup when the plugin is shut down.
     * Default no-op implementation.
     */
    default void shutdown() {
        // Default: no cleanup needed
    }

    /**
     * Returns the plugin version for compatibility checking.
     */
    default String version() {
        return "1.0.0";
    }
}
