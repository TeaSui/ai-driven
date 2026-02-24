package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for tenant configurations.
 *
 * <p>Supports dynamic registration of tenants at runtime (e.g., loaded from
 * DynamoDB or environment config). Designed for a modular monolith that can
 * evolve toward multi-tenancy without a full microservices rewrite.</p>
 *
 * <p>Thread-safe via ConcurrentHashMap.</p>
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
     * @return Optional containing the config, or empty if not found
     */
    public Optional<TenantConfig> getTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenants.get(tenantId));
    }

    /**
     * Returns all registered tenant configurations.
     */
    public Collection<TenantConfig> getAllTenants() {
        return Collections.unmodifiableCollection(tenants.values());
    }

    /**
     * Returns all active tenant configurations.
     */
    public Collection<TenantConfig> getActiveTenants() {
        return tenants.values().stream()
                .filter(TenantConfig::isActive)
                .toList();
    }

    /**
     * Removes a tenant from the registry.
     *
     * @param tenantId The tenant identifier to remove
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
     * Checks if a tenant is registered.
     */
    public boolean isRegistered(String tenantId) {
        return tenants.containsKey(tenantId);
    }

    /**
     * Returns the number of registered tenants.
     */
    public int size() {
        return tenants.size();
    }
}
