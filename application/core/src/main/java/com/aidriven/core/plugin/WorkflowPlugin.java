package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;

import java.util.Map;

/**
 * Contract for workflow automation plugins.
 * Each plugin represents a discrete, independently testable capability
 * that can be enabled per-tenant.
 *
 * <p>Plugins follow the same pattern as {@link com.aidriven.core.agent.tool.ToolProvider}:
 * register once, enable per-tenant via configuration.</p>
 *
 * <p>Examples:
 * <ul>
 *   <li>JiraWorkflowPlugin — Jira-specific workflow steps</li>
 *   <li>SlackNotificationPlugin — Slack notifications on PR creation</li>
 *   <li>SonarQubePlugin — Code quality gate checks</li>
 *   <li>JiraXrayPlugin — Test management integration</li>
 * </ul>
 */
public interface WorkflowPlugin {

    /**
     * Unique plugin identifier (e.g., "slack-notifications", "sonarqube").
     * Used for tenant-level enable/disable configuration.
     */
    String pluginId();

    /**
     * Human-readable plugin name.
     */
    String displayName();

    /**
     * Returns true if this plugin is applicable for the given tenant.
     * Implementations can check tenant labels, config, or feature flags.
     *
     * @param tenant The tenant configuration
     * @return true if the plugin should be active for this tenant
     */
    boolean isApplicable(TenantConfig tenant);

    /**
     * Initializes the plugin with tenant-specific configuration.
     * Called once per tenant when the plugin is activated.
     *
     * @param tenant     The tenant configuration
     * @param pluginConf Plugin-specific configuration map
     */
    void initialize(TenantConfig tenant, Map<String, String> pluginConf);

    /**
     * Returns the plugin's current health status.
     * Used for monitoring and diagnostics.
     */
    default PluginStatus getStatus() {
        return PluginStatus.ACTIVE;
    }

    /**
     * Plugin lifecycle status.
     */
    enum PluginStatus {
        ACTIVE,
        INACTIVE,
        ERROR,
        INITIALIZING
    }
}
