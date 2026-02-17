package com.aidriven.registry;

import java.util.Map;
import java.util.Optional;

/**
 * Holds tenant-specific configuration for multi-tenant deployments.
 * <p>
 * In single-tenant mode (e.g., current Lambda deployment), a default
 * tenant context is used with values from environment variables.
 * </p>
 * <p>
 * In multi-tenant SaaS mode, each request carries a tenant ID that
 * resolves to tenant-specific secrets, repositories, and tool configurations.
 * </p>
 */
public record TenantContext(
        String tenantId,
        String displayName,
        Map<String, String> secretArns,
        Map<String, String> configuration,
        Map<String, Boolean> enabledFeatures) {

    /** Default tenant for single-tenant / backward-compatible deployments. */
    public static final String DEFAULT_TENANT_ID = "default";

    /**
     * Creates a default (single-tenant) context from environment variables.
     */
    public static TenantContext defaultContext() {
        return new TenantContext(
                DEFAULT_TENANT_ID,
                "Default Tenant",
                Map.of(),
                Map.of(),
                Map.of());
    }

    /**
     * Gets a secret ARN by logical name (e.g., "claude", "jira", "bitbucket").
     */
    public Optional<String> getSecretArn(String name) {
        return Optional.ofNullable(secretArns.get(name));
    }

    /**
     * Gets a configuration value with a default fallback.
     */
    public String getConfig(String key, String defaultValue) {
        return configuration.getOrDefault(key, defaultValue);
    }

    /**
     * Checks if a feature is enabled for this tenant.
     */
    public boolean isFeatureEnabled(String feature) {
        return enabledFeatures.getOrDefault(feature, false);
    }

    /**
     * Returns true if this is the default single-tenant context.
     */
    public boolean isDefault() {
        return DEFAULT_TENANT_ID.equals(tenantId);
    }
}
