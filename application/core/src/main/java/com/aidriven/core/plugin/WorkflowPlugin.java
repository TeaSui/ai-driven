package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;

/**
 * Contract for workflow automation plugins.
 * Each plugin extends the system's capabilities for a specific domain
 * (e.g., monitoring, messaging, data, custom business logic).
 *
 * <p>Plugins are registered per-tenant and activated based on tenant
 * configuration. This enables different companies to have different
 * capabilities without code duplication.</p>
 *
 * <p>Plugin lifecycle:
 * <ol>
 *   <li>{@link #isEnabled(TenantConfig)} — checked before activation</li>
 *   <li>{@link #initialize(TenantConfig)} — called once per tenant on cold start</li>
 *   <li>Plugin provides tools via {@link #getToolProviderClass()}</li>
 *   <li>{@link #shutdown()} — called on Lambda shutdown (best-effort)</li>
 * </ol>
 */
public interface WorkflowPlugin {

    /**
     * Unique plugin identifier (e.g., "monitoring", "slack-messaging").
     * Must match the namespace used in ToolProvider.
     */
    String pluginId();

    /**
     * Human-readable plugin name.
     */
    String displayName();

    /**
     * Checks if this plugin should be activated for the given tenant.
     *
     * @param tenantConfig The tenant configuration
     * @return true if the plugin should be activated
     */
    boolean isEnabled(TenantConfig tenantConfig);

    /**
     * Initializes the plugin for a specific tenant.
     * Called once per tenant on Lambda cold start.
     *
     * @param tenantConfig The tenant configuration
     * @throws PluginInitializationException if initialization fails
     */
    void initialize(TenantConfig tenantConfig);

    /**
     * Returns the ToolProvider class this plugin contributes.
     * The ToolProvider is instantiated after {@link #initialize(TenantConfig)}.
     *
     * @return The ToolProvider class, or null if this plugin doesn't provide tools
     */
    default Class<?> getToolProviderClass() {
        return null;
    }

    /**
     * Shuts down the plugin, releasing any resources.
     * Called on Lambda shutdown (best-effort, may not always be called).
     */
    default void shutdown() {
        // Default no-op
    }

    /**
     * Exception thrown when plugin initialization fails.
     */
    class PluginInitializationException extends RuntimeException {
        public PluginInitializationException(String message) {
            super(message);
        }

        public PluginInitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
