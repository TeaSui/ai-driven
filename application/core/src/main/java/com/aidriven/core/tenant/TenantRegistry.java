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
 * <p>Supports dynamic registration of tenants at runtime (e.g., loaded from DynamoDB
 * or environment variables). Thread-safe for Lambda concurrent invocations.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * TenantRegistry registry = TenantRegistry.getInstance();
 * registry.register(TenantConfig.builder().tenantId("acme").build());
 * Optional<TenantConfig> config = registry.getTenant("acme");
 * }</pre>
 */
@Slf4j
public class TenantRegistry {

    private static final TenantRegistry INSTANCE = new TenantRegistry();

    private final Map<String, TenantConfig> tenants = new ConcurrentHashMap<>();

    private TenantRegistry() {}

    public static TenantRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a tenant configuration.
     * If a tenant with the same ID already exists, it is replaced.
     *
     * @param config The tenant configuration to register
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
     * Returns all registered tenants.
     */
    public Collection<TenantConfig> getAllTenants() {
        return Collections.unmodifiableCollection(tenants.values());
    }

    /**
     * Returns true if a tenant with the given ID is registered.
     */
    public boolean hasTenant(String tenantId) {
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
     * Returns the number of registered tenants.
     */
    public int size() {
        return tenants.size();
    }

    /**
     * Clears all registered tenants. Primarily for testing.
     */
    public void clear() {
        tenants.clear();
    }
}
