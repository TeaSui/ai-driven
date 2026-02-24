package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for tenant configurations.
 *
 * <p>Maintains a map of tenant ID → TenantConfig and provides
 * lookup, registration, and validation operations.
 *
 * <p>In production, tenant configs are loaded from DynamoDB or
 * AWS AppConfig at startup. In tests, configs are registered directly.
 *
 * <p>Thread-safe for concurrent Lambda invocations.
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
     * Looks up a tenant configuration by ID.
     *
     * @param tenantId The tenant identifier
     * @return Optional containing the config, or empty if not found
     */
    public Optional<TenantConfig> find(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenants.get(tenantId));
    }

    /**
     * Returns a tenant config or throws if not found.
     *
     * @param tenantId The tenant identifier
     * @return The tenant configuration
     * @throws TenantNotFoundException if the tenant is not registered
     */
    public TenantConfig getOrThrow(String tenantId) {
        return find(tenantId).orElseThrow(() ->
                new TenantNotFoundException("Tenant not found: " + tenantId));
    }

    /**
     * Returns all registered tenant IDs.
     */
    public Collection<String> getTenantIds() {
        return Collections.unmodifiableSet(tenants.keySet());
    }

    /**
     * Returns all registered tenant configurations.
     */
    public Collection<TenantConfig> getAllTenants() {
        return Collections.unmodifiableCollection(tenants.values());
    }

    /**
     * Checks if a tenant is registered.
     *
     * @param tenantId The tenant identifier
     * @return true if registered
     */
    public boolean isRegistered(String tenantId) {
        return tenantId != null && tenants.containsKey(tenantId);
    }

    /**
     * Removes a tenant registration.
     *
     * @param tenantId The tenant identifier
     */
    public void deregister(String tenantId) {
        TenantConfig removed = tenants.remove(tenantId);
        if (removed != null) {
            log.info("Deregistered tenant: {}", tenantId);
        }
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
        public TenantNotFoundException(String message) {
            super(message);
        }
    }
}
