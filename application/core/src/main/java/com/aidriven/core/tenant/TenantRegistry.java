package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for tenant configurations.
 * Supports dynamic registration and lookup of tenants.
 *
 * <p>In a Lambda environment, this is populated at cold start from
 * DynamoDB or environment variables. Tenants can be added/updated
 * without redeployment via the DynamoDB-backed implementation.</p>
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
     * Looks up a tenant by ID.
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
     * Returns all registered tenants.
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
     * Checks if a tenant is registered.
     */
    public boolean contains(String tenantId) {
        return tenants.containsKey(tenantId);
    }

    /**
     * Removes a tenant from the registry.
     */
    public void deregister(String tenantId) {
        TenantConfig removed = tenants.remove(tenantId);
        if (removed != null) {
            log.info("Deregistered tenant: {}", tenantId);
        }
    }

    /**
     * Updates an existing tenant configuration.
     * If the tenant does not exist, it is registered.
     */
    public void update(TenantConfig config) {
        register(config);
        log.info("Updated tenant config: {}", config.getTenantId());
    }
}
