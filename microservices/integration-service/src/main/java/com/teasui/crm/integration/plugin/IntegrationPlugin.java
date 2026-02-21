package com.teasui.crm.integration.plugin;

import java.util.Map;

/**
 * Plugin interface for custom integrations.
 * Implement this interface to add support for new third-party tools.
 * Each plugin is independently deployable and configurable per tenant.
 */
public interface IntegrationPlugin {

    /**
     * Returns the unique identifier for this plugin type.
     */
    String getPluginType();

    /**
     * Returns a human-readable name for this plugin.
     */
    String getDisplayName();

    /**
     * Returns a description of what this plugin does.
     */
    String getDescription();

    /**
     * Validates the provided configuration for this plugin.
     *
     * @param config the configuration map to validate
     * @throws IllegalArgumentException if configuration is invalid
     */
    void validateConfig(Map<String, Object> config);

    /**
     * Tests the connection with the provided credentials.
     *
     * @param config      the plugin configuration
     * @param credentials the authentication credentials
     * @return true if connection is successful
     */
    boolean testConnection(Map<String, Object> config, Map<String, Object> credentials);

    /**
     * Executes the integration action.
     *
     * @param config      the plugin configuration
     * @param credentials the authentication credentials
     * @param payload     the data payload to process
     * @return the result of the execution
     */
    Map<String, Object> execute(Map<String, Object> config, Map<String, Object> credentials, Map<String, Object> payload);
}
