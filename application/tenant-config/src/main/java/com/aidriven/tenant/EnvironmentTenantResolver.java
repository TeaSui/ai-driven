package com.aidriven.tenant;

import com.aidriven.contracts.config.TenantConfiguration;
import com.aidriven.contracts.config.TenantResolver;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Simple tenant resolver that reads configuration from environment variables.
 * <p>
 * This is the default resolver for single-tenant deployments where all
 * configuration comes from Lambda environment variables (the current behavior).
 * </p>
 *
 * <p>
 * It always returns the same default configuration regardless of project key
 * or tenant ID, maintaining backward compatibility with the existing system.
 * </p>
 */
@Slf4j
public class EnvironmentTenantResolver implements TenantResolver {

    private final TenantConfiguration defaultConfig;

    public EnvironmentTenantResolver() {
        this.defaultConfig = buildFromEnvironment();
    }

    /** Constructor for testing with explicit config. */
    public EnvironmentTenantResolver(TenantConfiguration config) {
        this.defaultConfig = config;
    }

    @Override
    public Optional<TenantConfiguration> resolveByProjectKey(String projectKey) {
        // Single-tenant: always returns the default
        return Optional.of(defaultConfig);
    }

    @Override
    public Optional<TenantConfiguration> resolveById(String tenantId) {
        // Single-tenant: always returns the default
        return Optional.of(defaultConfig);
    }

    @Override
    public TenantConfiguration getDefault() {
        return defaultConfig;
    }

    private TenantConfiguration buildFromEnvironment() {
        String defaultPlatform = getEnv("DEFAULT_PLATFORM", "BITBUCKET");

        var scConfig = new TenantConfiguration.SourceControlConfig(
                defaultPlatform,
                getEnv("BITBUCKET_SECRET_ARN", null),
                getEnv("DEFAULT_WORKSPACE", null),
                getEnv("DEFAULT_REPO", null));

        var itConfig = new TenantConfiguration.IssueTrackerConfig(
                "JIRA",
                getEnv("JIRA_SECRET_ARN", null),
                null);

        var aiConfig = new TenantConfiguration.AiModelConfig(
                "claude",
                getEnv("CLAUDE_SECRET_ARN", null),
                getEnv("CLAUDE_MODEL", "claude-opus-4-6"),
                getIntEnv("CLAUDE_MAX_TOKENS", 32768),
                getDoubleEnv("CLAUDE_TEMPERATURE", 0.2));

        return new TenantConfiguration(
                "default",
                "Default Tenant",
                scConfig,
                itConfig,
                aiConfig,
                List.of("source_control", "issue_tracker", "code_context"),
                Map.of(
                        "branch_prefix", getEnv("BRANCH_PREFIX", "ai/"),
                        "context_mode", getEnv("CONTEXT_MODE", "FULL_REPO")));
    }

    private String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private int getIntEnv(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultValue; }
    }

    private double getDoubleEnv(String key, double defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return defaultValue; }
    }
}
