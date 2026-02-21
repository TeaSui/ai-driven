package com.aidriven.core.tenant;

import com.aidriven.core.config.AgentConfig;
import com.aidriven.core.config.AppConfig;

import java.util.List;

/**
 * Provides tenant-aware configuration by merging global defaults
 * with per-tenant overrides from {@link TenantConfig}.
 *
 * <p>Resolution order (highest priority first):
 * <ol>
 *   <li>Tenant-specific config (from DynamoDB)</li>
 *   <li>Global defaults (from environment variables via {@link AppConfig})</li>
 * </ol>
 */
public class TenantAwareConfig {

    private final AppConfig globalConfig;
    private final TenantConfig tenantConfig;

    public TenantAwareConfig(AppConfig globalConfig, TenantConfig tenantConfig) {
        this.globalConfig = globalConfig;
        this.tenantConfig = tenantConfig;
    }

    /**
     * Creates a TenantAwareConfig using the default tenant (single-tenant mode).
     */
    public static TenantAwareConfig withGlobalDefaults(AppConfig globalConfig) {
        return new TenantAwareConfig(globalConfig, null);
    }

    /**
     * Returns the effective AI model for this tenant.
     */
    public String getAiModel() {
        if (tenantConfig != null) {
            return tenantConfig.effectiveAiModel(globalConfig.getClaudeModel());
        }
        return globalConfig.getClaudeModel();
    }

    /**
     * Returns the effective issue tracker secret ARN for this tenant.
     */
    public String getIssueTrackerSecretArn() {
        if (tenantConfig != null && tenantConfig.getIssueTrackerSecretArn() != null) {
            return tenantConfig.getIssueTrackerSecretArn();
        }
        return globalConfig.getJiraSecretArn();
    }

    /**
     * Returns the effective source control secret ARN for this tenant.
     */
    public String getSourceControlSecretArn() {
        if (tenantConfig != null && tenantConfig.getSourceControlSecretArn() != null) {
            return tenantConfig.getSourceControlSecretArn();
        }
        // Fall back based on platform type
        String platform = getSourceControlType();
        if ("GITHUB".equalsIgnoreCase(platform)) {
            return globalConfig.getGitHubSecretArn();
        }
        return globalConfig.getBitbucketSecretArn();
    }

    /**
     * Returns the effective source control type for this tenant.
     */
    public String getSourceControlType() {
        if (tenantConfig != null && tenantConfig.getSourceControlType() != null) {
            return tenantConfig.getSourceControlType();
        }
        return globalConfig.getDefaultPlatform();
    }

    /**
     * Returns the effective issue tracker type for this tenant.
     */
    public String getIssueTrackerType() {
        if (tenantConfig != null && tenantConfig.getIssueTrackerType() != null) {
            return tenantConfig.getIssueTrackerType();
        }
        return "JIRA"; // Default
    }

    /**
     * Returns the effective default workspace for this tenant.
     */
    public String getDefaultWorkspace() {
        if (tenantConfig != null && tenantConfig.getDefaultWorkspace() != null) {
            return tenantConfig.getDefaultWorkspace();
        }
        return globalConfig.getDefaultWorkspace();
    }

    /**
     * Returns the effective default repo for this tenant.
     */
    public String getDefaultRepo() {
        if (tenantConfig != null && tenantConfig.getDefaultRepo() != null) {
            return tenantConfig.getDefaultRepo();
        }
        return globalConfig.getDefaultRepo();
    }

    /**
     * Returns the effective max tokens per ticket for this tenant.
     */
    public int getMaxTokensPerTicket() {
        if (tenantConfig != null) {
            return tenantConfig.effectiveMaxTokensPerTicket(
                    globalConfig.getAgentConfig().costBudgetPerTicket());
        }
        return globalConfig.getAgentConfig().costBudgetPerTicket();
    }

    /**
     * Returns the MCP servers JSON config for this tenant.
     */
    public String getMcpServersJson() {
        if (tenantConfig != null && tenantConfig.getMcpServersJson() != null) {
            return tenantConfig.getMcpServersJson();
        }
        return globalConfig.getMcpServersConfig();
    }

    /**
     * Returns the enabled modules for this tenant.
     */
    public List<String> getEnabledModules() {
        if (tenantConfig != null && tenantConfig.getEnabledModules() != null) {
            return tenantConfig.getEnabledModules();
        }
        // Default: all core modules enabled
        return List.of("source_control", "issue_tracker", "code_context");
    }

    /**
     * Returns true if the given module is enabled for this tenant.
     */
    public boolean isModuleEnabled(String namespace) {
        if (tenantConfig != null) {
            return tenantConfig.isModuleEnabled(namespace);
        }
        // Default: core modules always enabled
        return "source_control".equals(namespace)
                || "issue_tracker".equals(namespace)
                || "code_context".equals(namespace);
    }

    /**
     * Returns the underlying tenant config (may be null for default tenant).
     */
    public TenantConfig getTenantConfig() {
        return tenantConfig;
    }

    /**
     * Returns the global app config.
     */
    public AppConfig getGlobalConfig() {
        return globalConfig;
    }
}
