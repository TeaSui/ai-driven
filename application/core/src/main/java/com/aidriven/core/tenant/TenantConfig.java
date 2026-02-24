package com.aidriven.core.tenant;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tenant-specific configuration for workflow automation.
 * Each tenant (company) has its own configuration that customizes
 * the behavior of the workflow automation system.
 */
@Data
@Builder(toBuilder = true)
public class TenantConfig {

    /** Unique tenant identifier (e.g., "acme-corp", "startup-inc") */
    private final String tenantId;

    /** Human-readable tenant name */
    private final String tenantName;

    /** Source control platform preference */
    private final String defaultPlatform;

    /** Default workspace/organization for source control */
    private final String defaultWorkspace;

    /** Default repository slug */
    private final String defaultRepo;

    /** Jira project keys this tenant uses */
    private final List<String> jiraProjectKeys;

    /** Enabled workflow plugins for this tenant */
    private final Set<String> enabledPlugins;

    /** Tenant-specific plugin configuration (plugin name -> config map) */
    private final Map<String, Map<String, String>> pluginConfig;

    /** AWS Secrets Manager ARN prefix for tenant secrets */
    private final String secretsArnPrefix;

    /** Maximum concurrent workflow executions */
    @Builder.Default
    private final int maxConcurrentWorkflows = 10;

    /** Whether agent mode is enabled for this tenant */
    @Builder.Default
    private final boolean agentEnabled = false;

    /** Tenant-specific Claude model override (null = use system default) */
    private final String claudeModelOverride;

    /** Custom branch prefix for generated branches */
    @Builder.Default
    private final String branchPrefix = "ai/";

    /** Whether dry-run mode is forced for this tenant (safety net) */
    @Builder.Default
    private final boolean forceDryRun = false;

    /**
     * Returns true if the given plugin is enabled for this tenant.
     */
    public boolean isPluginEnabled(String pluginName) {
        if (enabledPlugins == null || enabledPlugins.isEmpty()) {
            return false;
        }
        return enabledPlugins.contains(pluginName);
    }

    /**
     * Returns plugin-specific configuration for the given plugin.
     */
    public Map<String, String> getPluginConfig(String pluginName) {
        if (pluginConfig == null) {
            return Map.of();
        }
        return pluginConfig.getOrDefault(pluginName, Map.of());
    }

    /**
     * Returns the effective Claude model for this tenant.
     */
    public String getEffectiveClaudeModel(String systemDefault) {
        return claudeModelOverride != null && !claudeModelOverride.isBlank()
                ? claudeModelOverride
                : systemDefault;
    }
}
