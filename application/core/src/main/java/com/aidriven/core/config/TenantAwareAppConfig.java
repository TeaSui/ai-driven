package com.aidriven.core.config;

import java.util.Map;
import java.util.Optional;

/**
 * Tenant-aware configuration that overlays tenant-specific settings
 * on top of the base {@link AppConfig}.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>Tenant-specific config (from TenantConfig)</li>
 *   <li>Base AppConfig (environment variables)</li>
 * </ol>
 *
 * <p>This allows tenants to override defaults (e.g., Claude model, branch prefix)
 * while inheriting infrastructure settings (DynamoDB table, S3 bucket).</p>
 */
public class TenantAwareAppConfig {

    private final AppConfig baseConfig;
    private final String tenantId;
    private final Map<String, String> tenantOverrides;

    public TenantAwareAppConfig(AppConfig baseConfig, String tenantId, Map<String, String> tenantOverrides) {
        this.baseConfig = baseConfig;
        this.tenantId = tenantId;
        this.tenantOverrides = tenantOverrides != null ? Map.copyOf(tenantOverrides) : Map.of();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getClaudeModel() {
        return getOverride("claude.model").orElse(baseConfig.getClaudeModel());
    }

    public int getClaudeMaxTokens() {
        return getOverride("claude.maxTokens")
                .map(Integer::parseInt)
                .orElse(baseConfig.getClaudeMaxTokens());
    }

    public double getClaudeTemperature() {
        return getOverride("claude.temperature")
                .map(Double::parseDouble)
                .orElse(baseConfig.getClaudeTemperature());
    }

    public String getBranchPrefix() {
        return getOverride("branch.prefix").orElse(baseConfig.getBranchPrefix());
    }

    public int getMaxContextForClaude() {
        return getOverride("claude.maxContext")
                .map(Integer::parseInt)
                .orElse(baseConfig.getMaxContextForClaude());
    }

    public String getDefaultPlatform() {
        return getOverride("platform.default").orElse(baseConfig.getDefaultPlatform());
    }

    public String getDefaultWorkspace() {
        return getOverride("repo.workspace").orElse(baseConfig.getDefaultWorkspace());
    }

    public String getDefaultRepo() {
        return getOverride("repo.slug").orElse(baseConfig.getDefaultRepo());
    }

    /**
     * Get the DynamoDB table name — always from base config (shared infrastructure).
     */
    public String getDynamoDbTableName() {
        return baseConfig.getDynamoDbTableName();
    }

    /**
     * Get the S3 bucket — always from base config (shared infrastructure).
     */
    public String getCodeContextBucket() {
        return baseConfig.getCodeContextBucket();
    }

    /**
     * Get a tenant-specific secret ARN, falling back to base config.
     */
    public String getClaudeSecretArn() {
        return getOverride("claude.secretArn").orElse(baseConfig.getClaudeSecretArn());
    }

    public String getBitbucketSecretArn() {
        return getOverride("bitbucket.secretArn").orElse(baseConfig.getBitbucketSecretArn());
    }

    public String getGitHubSecretArn() {
        return getOverride("github.secretArn").orElse(baseConfig.getGitHubSecretArn());
    }

    public String getJiraSecretArn() {
        return getOverride("jira.secretArn").orElse(baseConfig.getJiraSecretArn());
    }

    /**
     * Get the base AppConfig for accessing non-overridable settings.
     */
    public AppConfig getBaseConfig() {
        return baseConfig;
    }

    private Optional<String> getOverride(String key) {
        String value = tenantOverrides.get(key);
        return (value != null && !value.isBlank()) ? Optional.of(value) : Optional.empty();
    }
}
