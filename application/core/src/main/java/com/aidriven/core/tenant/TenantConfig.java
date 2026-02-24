package com.aidriven.core.tenant;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tenant-specific configuration for workflow automation.
 * Each tenant (company) has its own configuration that customizes
 * the behavior of the AI-driven system.
 *
 * <p>Supports:
 * <ul>
 *   <li>Custom tool plugins per tenant</li>
 *   <li>Tenant-specific AI model preferences</li>
 *   <li>Custom Jira/source control configurations</li>
 *   <li>Feature flags per tenant</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
public class TenantConfig {

    /** Unique tenant identifier (e.g., "acme-corp", "startup-xyz") */
    private final String tenantId;

    /** Human-readable tenant name */
    private final String tenantName;

    /** Jira secret ARN for this tenant */
    private final String jiraSecretArn;

    /** Source control secret ARN for this tenant */
    private final String sourceControlSecretArn;

    /** Claude API secret ARN for this tenant (optional, falls back to global) */
    private final String claudeSecretArn;

    /** Default source control platform for this tenant */
    private final String defaultPlatform;

    /** Default workspace/org for this tenant */
    private final String defaultWorkspace;

    /** Default repository for this tenant */
    private final String defaultRepo;

    /** Enabled plugin namespaces for this tenant (e.g., ["monitoring", "messaging"]) */
    private final Set<String> enabledPlugins;

    /** Tenant-specific feature flags */
    private final Map<String, Boolean> featureFlags;

    /** Custom labels that trigger AI processing for this tenant */
    private final List<String> triggerLabels;

    /** Maximum tokens per conversation for this tenant */
    private final int tokenBudget;

    /** Maximum turns per agent session for this tenant */
    private final int maxTurns;

    /** Whether guardrails are enabled for this tenant */
    private final boolean guardrailsEnabled;

    /** Tenant-specific MCP server configurations (JSON) */
    private final String mcpServersConfig;

    /** Whether this tenant is active */
    private final boolean active;

    /**
     * Checks if a specific plugin is enabled for this tenant.
     *
     * @param pluginNamespace The plugin namespace (e.g., "monitoring")
     * @return true if the plugin is enabled
     */
    public boolean isPluginEnabled(String pluginNamespace) {
        if (enabledPlugins == null) return false;
        return enabledPlugins.contains(pluginNamespace);
    }

    /**
     * Checks if a feature flag is enabled for this tenant.
     *
     * @param flagName The feature flag name
     * @return true if the flag is enabled, false if disabled or not set
     */
    public boolean isFeatureEnabled(String flagName) {
        if (featureFlags == null) return false;
        return Boolean.TRUE.equals(featureFlags.get(flagName));
    }

    /**
     * Returns the effective token budget (falls back to default if not set).
     */
    public int getEffectiveTokenBudget(int defaultBudget) {
        return tokenBudget > 0 ? tokenBudget : defaultBudget;
    }

    /**
     * Returns the effective max turns (falls back to default if not set).
     */
    public int getEffectiveMaxTurns(int defaultMaxTurns) {
        return maxTurns > 0 ? maxTurns : defaultMaxTurns;
    }

    /**
     * Creates a default tenant config for single-tenant deployments.
     * Uses global environment variables as defaults.
     */
    public static TenantConfig defaultTenant(String tenantId) {
        return TenantConfig.builder()
                .tenantId(tenantId)
                .tenantName(tenantId)
                .active(true)
                .guardrailsEnabled(true)
                .tokenBudget(200_000)
                .maxTurns(10)
                .build();
    }
}
