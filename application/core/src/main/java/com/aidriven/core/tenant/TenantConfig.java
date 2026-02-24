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
 *
 * <p>Supports:
 * <ul>
 *   <li>Custom tool plugin registration per tenant</li>
 *   <li>Tenant-specific source control and issue tracker settings</li>
 *   <li>Custom workflow steps and approval policies</li>
 *   <li>Feature flags per tenant</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
public class TenantConfig {

    /** Unique tenant identifier (e.g., "acme-corp", "startup-xyz"). */
    private String tenantId;

    /** Human-readable tenant name. */
    private String tenantName;

    /** AWS region for tenant-specific infrastructure. */
    private String awsRegion;

    /** DynamoDB table name for this tenant (supports table-per-tenant isolation). */
    private String dynamoDbTableName;

    /** S3 bucket for code context storage. */
    private String codeContextBucket;

    /** Jira secret ARN for this tenant. */
    private String jiraSecretArn;

    /** Bitbucket secret ARN for this tenant. */
    private String bitbucketSecretArn;

    /** GitHub secret ARN for this tenant. */
    private String gitHubSecretArn;

    /** Claude API secret ARN for this tenant. */
    private String claudeSecretArn;

    /** Default source control platform (BITBUCKET or GITHUB). */
    private String defaultPlatform;

    /** Default workspace/organization for source control. */
    private String defaultWorkspace;

    /** Default repository slug. */
    private String defaultRepo;

    /** Enabled plugin namespaces for this tenant (e.g., "monitoring", "messaging"). */
    private Set<String> enabledPlugins;

    /** Tenant-specific feature flags. */
    private Map<String, Boolean> featureFlags;

    /** Custom workflow steps configuration. */
    private List<WorkflowStepConfig> workflowSteps;

    /** Agent configuration overrides for this tenant. */
    private TenantAgentConfig agentConfig;

    /** MCP server configurations specific to this tenant. */
    private String mcpServersConfig;

    /** Branch prefix for AI-generated branches. */
    @Builder.Default
    private String branchPrefix = "ai/";

    /** Maximum context size for Claude (chars). */
    @Builder.Default
    private int maxContextForClaude = 700_000;

    /** Claude model to use. */
    @Builder.Default
    private String claudeModel = "claude-opus-4-6";

    /**
     * Checks if a specific feature flag is enabled for this tenant.
     *
     * @param flag Feature flag name
     * @return true if enabled, false if disabled or not configured
     */
    public boolean isFeatureEnabled(String flag) {
        if (featureFlags == null) return false;
        return Boolean.TRUE.equals(featureFlags.get(flag));
    }

    /**
     * Checks if a plugin is enabled for this tenant.
     *
     * @param namespace Plugin namespace (e.g., "monitoring")
     * @return true if enabled
     */
    public boolean isPluginEnabled(String namespace) {
        if (enabledPlugins == null) return false;
        return enabledPlugins.contains(namespace);
    }

    /**
     * Returns the effective DynamoDB table name, falling back to a default pattern.
     */
    public String effectiveTableName(String globalDefault) {
        return dynamoDbTableName != null ? dynamoDbTableName : globalDefault;
    }
}
