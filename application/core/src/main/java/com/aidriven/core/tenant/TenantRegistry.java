package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing tenant configurations.
 *
 * <p>Supports dynamic registration of tenants at runtime,
 * enabling multi-tenant deployments without code changes.
 *
 * <p>Thread-safe: uses ConcurrentHashMap for concurrent access.
 */
@Slf4j
public class TenantRegistry {

    private final Map<String, TenantConfig> tenants = new ConcurrentHashMap<>();

    /**
     * Registers a tenant configuration.
     *
     * @param config The tenant configuration to register
     * @throws IllegalArgumentException if tenantId is null or blank
     */
    public void register(TenantConfig config) {
        if (config == null || config.getTenantId() == null || config.getTenantId().isBlank()) {
            throw new IllegalArgumentException("TenantConfig must have a non-blank tenantId");
        }
        tenants.put(config.getTenantId(), config);
        log.info("Registered tenant: {} ({})", config.getTenantId(), config.getTenantName());
    }

    /**
     * Retrieves a tenant configuration by ID.
     *
     * @param tenantId The tenant identifier
     * @return Optional containing the tenant config, or empty if not found
     */
    public Optional<TenantConfig> getTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenants.get(tenantId));
    }

    /**
     * Retrieves a tenant configuration, throwing if not found.
     *
     * @param tenantId The tenant identifier
     * @return The tenant configuration
     * @throws TenantNotFoundException if the tenant is not registered
     */
    public TenantConfig getRequiredTenant(String tenantId) {
        return getTenant(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    /**
     * Checks if a tenant is registered and active.
     *
     * @param tenantId The tenant identifier
     * @return true if the tenant exists and is active
     */
    public boolean isActive(String tenantId) {
        return getTenant(tenantId)
                .map(TenantConfig::isActive)
                .orElse(false);
    }

    /**
     * Removes a tenant from the registry.
     *
     * @param tenantId The tenant identifier
     * @return true if the tenant was removed, false if not found
     */
    public boolean deregister(String tenantId) {
        boolean removed = tenants.remove(tenantId) != null;
        if (removed) {
            log.info("Deregistered tenant: {}", tenantId);
        }
        return removed;
    }

    /**
     * Returns all registered tenant configurations.
     */
    public Collection<TenantConfig> getAllTenants() {
        return tenants.values();
    }

    /**
     * Returns the number of registered tenants.
     */
    public int size() {
        return tenants.size();
    }

    /**
     * Exception thrown when a tenant is not found in the registry.
     */
    public static class TenantNotFoundException extends RuntimeException {
        public TenantNotFoundException(String tenantId) {
            super("Tenant not found: " + tenantId);
        }
    }
}
