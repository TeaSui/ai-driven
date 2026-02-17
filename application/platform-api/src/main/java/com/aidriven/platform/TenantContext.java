package com.aidriven.platform;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable context representing the current tenant for a request.
 * Carries tenant-specific configuration needed to route to the correct
 * integrations (source control, issue tracker, AI provider, etc.).
 *
 * <p>Thread-safe and suitable for passing through async boundaries.</p>
 */
public record TenantContext(
        String tenantId,
        String tenantName,
        Map<String, String> config) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(tenantName, "tenantName must not be null");
        config = config != null ? Collections.unmodifiableMap(config) : Map.of();
    }

    /**
     * Gets a tenant-specific configuration value.
     *
     * @param key          The configuration key
     * @param defaultValue Fallback if key is absent
     * @return The configuration value or default
     */
    public String getConfigValue(String key, String defaultValue) {
        return config.getOrDefault(key, defaultValue);
    }

    /**
     * Gets a required tenant-specific configuration value.
     *
     * @param key The configuration key
     * @return The configuration value
     * @throws IllegalStateException if the key is not present
     */
    public String getRequiredConfigValue(String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    String.format("Missing required config '%s' for tenant '%s'", key, tenantId));
        }
        return value;
    }

    /**
     * Creates a minimal tenant context for single-tenant / default mode.
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
