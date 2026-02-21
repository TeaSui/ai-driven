package com.aidriven.core.tenant;

import java.util.Map;
import java.util.Optional;

/**
 * Immutable tenant context propagated through the processing pipeline.
 * Each Lambda invocation or API request carries a TenantContext that determines
 * which configuration, credentials, and integrations to use.
 *
 * <p>Thread-safe: stored in a ThreadLocal for the duration of a request.</p>
 */
public record TenantContext(
        String tenantId,
        String tenantName,
        String environment,
        Map<String, String> secretArns,
        Map<String, String> configuration,
        Map<String, Boolean> enabledModules) {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    /**
     * Sets the current tenant context for this thread.
     */
    public static void setCurrent(TenantContext context) {
        CURRENT.set(context);
    }

    /**
     * Gets the current tenant context for this thread.
     *
     * @return Optional containing the context, or empty if not set
     */
    public static Optional<TenantContext> getCurrent() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Clears the current tenant context. Should be called in finally blocks.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Gets a secret ARN by logical name (e.g., "jira", "bitbucket", "claude").
     *
     * @param name Logical secret name
     * @return The ARN, or empty if not configured for this tenant
     */
    public Optional<String> getSecretArn(String name) {
        if (secretArns == null) return Optional.empty();
        return Optional.ofNullable(secretArns.get(name));
    }

    /**
     * Gets a configuration value by key.
     *
     * @param key Configuration key
     * @return The value, or empty if not set
     */
    public Optional<String> getConfig(String key) {
        if (configuration == null) return Optional.empty();
        return Optional.ofNullable(configuration.get(key));
    }

    /**
     * Gets a configuration value with a default fallback.
     */
    public String getConfig(String key, String defaultValue) {
        return getConfig(key).orElse(defaultValue);
    }

    /**
     * Checks if a module is enabled for this tenant.
     *
     * @param moduleName Module name (e.g., "jira", "github", "monitoring")
     * @return true if the module is enabled
     */
    public boolean isModuleEnabled(String moduleName) {
        if (enabledModules == null) return false;
        return Boolean.TRUE.equals(enabledModules.get(moduleName));
    }

    /**
     * Creates a default single-tenant context for backward compatibility.
     * Uses environment variables as the configuration source.
     */
    public static TenantContext defaultContext() {
        return new TenantContext(
                "default",
                "Default Tenant",
                getEnvOrDefault("ENVIRONMENT", "production"),
                Map.of(
                        "jira", getEnvOrDefault("JIRA_SECRET_ARN", ""),
                        "bitbucket", getEnvOrDefault("BITBUCKET_SECRET_ARN", ""),
                        "github", getEnvOrDefault("GITHUB_SECRET_ARN", ""),
                        "claude", getEnvOrDefault("CLAUDE_SECRET_ARN", "")),
                Map.of(
                        "defaultPlatform", getEnvOrDefault("DEFAULT_PLATFORM", "BITBUCKET"),
                        "defaultWorkspace", getEnvOrDefault("DEFAULT_WORKSPACE", ""),
                        "defaultRepo", getEnvOrDefault("DEFAULT_REPO", "")),
                Map.of(
                        "jira", true,
                        "bitbucket", true,
                        "github", true,
                        "claude", true,
                        "agent", Boolean.parseBoolean(getEnvOrDefault("AGENT_ENABLED", "false"))));
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}