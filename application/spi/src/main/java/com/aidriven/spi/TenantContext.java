package com.aidriven.spi;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Tenant-specific configuration context passed to modules during initialization.
 *
 * <p>Each tenant (company) has its own configuration including:
 * <ul>
 *   <li>Tenant identifier</li>
 *   <li>Module-specific configuration key-value pairs</li>
 *   <li>Secret ARNs for credential resolution</li>
 *   <li>Feature flags</li>
 * </ul>
 *
 * <p>Configuration keys follow the convention: {@code module.property}
 * (e.g., {@code jira.baseUrl}, {@code github.token.arn}).</p>
 */
public class TenantContext {

    private final String tenantId;
    private final String tenantName;
    private final Map<String, String> configuration;
    private final Map<String, String> secretArns;
    private final Set<String> enabledModules;
    private final Map<String, Boolean> featureFlags;

    private TenantContext(Builder builder) {
        this.tenantId = Objects.requireNonNull(builder.tenantId, "tenantId");
        this.tenantName = builder.tenantName != null ? builder.tenantName : builder.tenantId;
        this.configuration = Collections.unmodifiableMap(new HashMap<>(builder.configuration));
        this.secretArns = Collections.unmodifiableMap(new HashMap<>(builder.secretArns));
        this.enabledModules = Collections.unmodifiableSet(new java.util.HashSet<>(builder.enabledModules));
        this.featureFlags = Collections.unmodifiableMap(new HashMap<>(builder.featureFlags));
    }

    public String getTenantId() { return tenantId; }
    public String getTenantName() { return tenantName; }

    /**
     * Gets a configuration value.
     *
     * @param key Configuration key (e.g., "jira.baseUrl")
     * @return The value, or empty if not set
     */
    public Optional<String> getConfig(String key) {
        return Optional.ofNullable(configuration.get(key));
    }

    /**
     * Gets a configuration value with a default fallback.
     */
    public String getConfig(String key, String defaultValue) {
        return configuration.getOrDefault(key, defaultValue);
    }

    /**
     * Gets a required configuration value.
     *
     * @throws IllegalStateException if the key is not configured
     */
    public String getRequiredConfig(String key) {
        String value = configuration.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    String.format("Required configuration '%s' not set for tenant '%s'", key, tenantId));
        }
        return value;
    }

    /**
     * Gets a secret ARN for credential resolution.
     */
    public Optional<String> getSecretArn(String key) {
        return Optional.ofNullable(secretArns.get(key));
    }

    /**
     * Checks if a module is enabled for this tenant.
     */
    public boolean isModuleEnabled(String moduleId) {
        return enabledModules.contains(moduleId);
    }

    /**
     * Checks a feature flag.
     */
    public boolean isFeatureEnabled(String flag) {
        return featureFlags.getOrDefault(flag, false);
    }

    /**
     * Returns all configuration entries (read-only).
     */
    public Map<String, String> getAllConfig() {
        return configuration;
    }

    /**
     * Returns all enabled module IDs.
     */
    public Set<String> getEnabledModules() {
        return enabledModules;
    }

    public static Builder builder(String tenantId) {
        return new Builder(tenantId);
    }

    /**
     * Creates a default context for backward compatibility (single-tenant mode).
     * Reads configuration from environment variables.
     */
    public static TenantContext fromEnvironment() {
        return fromEnvironment("default");
    }

    /**
     * Creates a context from environment variables with a specific tenant ID.
     */
    public static TenantContext fromEnvironment(String tenantId) {
        Builder builder = builder(tenantId).tenantName("Default Tenant");

        // Map existing env vars to tenant config
        mapEnv(builder, "jira.secretArn", "JIRA_SECRET_ARN");
        mapEnv(builder, "bitbucket.secretArn", "BITBUCKET_SECRET_ARN");
        mapEnv(builder, "github.secretArn", "GITHUB_SECRET_ARN");
        mapEnv(builder, "claude.secretArn", "CLAUDE_SECRET_ARN");
        mapEnv(builder, "dynamodb.tableName", "DYNAMODB_TABLE_NAME");
        mapEnv(builder, "s3.codeContextBucket", "CODE_CONTEXT_BUCKET");
        mapEnv(builder, "claude.model", "CLAUDE_MODEL");
        mapEnv(builder, "claude.maxTokens", "CLAUDE_MAX_TOKENS");
        mapEnv(builder, "agent.queueUrl", "AGENT_QUEUE_URL");
        mapEnv(builder, "platform.default", "DEFAULT_PLATFORM");

        // Enable all modules by default in single-tenant mode
        builder.enableModule("jira-client")
               .enableModule("bitbucket-client")
               .enableModule("github-client")
               .enableModule("claude-client")
               .enableModule("tool-source-control")
               .enableModule("tool-issue-tracker")
               .enableModule("tool-code-context");

        return builder.build();
    }

    private static void mapEnv(Builder builder, String configKey, String envKey) {
        String value = System.getenv(envKey);
        if (value != null && !value.isBlank()) {
            builder.config(configKey, value);
        }
    }

    public static class Builder {
        private final String tenantId;
        private String tenantName;
        private final Map<String, String> configuration = new HashMap<>();
        private final Map<String, String> secretArns = new HashMap<>();
        private final Set<String> enabledModules = new java.util.HashSet<>();
        private final Map<String, Boolean> featureFlags = new HashMap<>();

        Builder(String tenantId) {
            this.tenantId = tenantId;
        }

        public Builder tenantName(String name) { this.tenantName = name; return this; }
        public Builder config(String key, String value) { this.configuration.put(key, value); return this; }
        public Builder secretArn(String key, String arn) { this.secretArns.put(key, arn); return this; }
        public Builder enableModule(String moduleId) { this.enabledModules.add(moduleId); return this; }
        public Builder featureFlag(String flag, boolean enabled) { this.featureFlags.put(flag, enabled); return this; }

        public TenantContext build() {
            return new TenantContext(this);
        }
    }
}
