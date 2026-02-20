package com.aidriven.spi;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable context representing the current tenant.
 * Passed through the processing pipeline to resolve tenant-specific
 * configurations and service implementations.
 *
 * <p>In a multi-tenant SaaS deployment, each request is associated
 * with a tenant context that determines which integrations to use.</p>
 */
public record TenantContext(
        String tenantId,
        String tenantName,
        Map<String, String> configuration) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(tenantName, "tenantName must not be null");
        if (configuration == null) {
            configuration = Map.of();
        }
    }

    /**
     * Gets a configuration value for this tenant.
     */
    public String getConfig(String key) {
        return configuration.get(key);
    }

    /**
     * Gets a configuration value with a default fallback.
     */
    public String getConfig(String key, String defaultValue) {
        return configuration.getOrDefault(key, defaultValue);
    }

    /**
     * Creates a default/single-tenant context for backward compatibility.
     */
    public static TenantContext defaultTenant() {
        return new TenantContext("default", "Default Tenant", Map.of());
    }

    /**
     * Creates a tenant context with the given ID and configuration.
     */
    public static TenantContext of(String tenantId, String tenantName, Map<String, String> config) {
        return new TenantContext(tenantId, tenantName, config);
    }
}
