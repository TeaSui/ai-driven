package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for tenant configurations.
 * Supports dynamic registration and lookup of tenant configs.
 *
 * <p>In production, tenants are loaded from DynamoDB or environment config.
 * In tests, tenants can be registered programmatically.</p>
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
        return Collections.unmodifiableCollection(tenants.values());
    }

    /**
     * Returns the number of registered tenants.
     */
    public int size() {
        return tenants.size();
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
     * Resolves the tenant for a given Jira project key.
     * Searches all tenants for one that includes the project key.
     *
     * @param jiraProjectKey The Jira project key (e.g., "PROJ")
     * @return Optional containing the matching tenant, or empty if not found
     */
    public Optional<TenantConfig> resolveByJiraProject(String jiraProjectKey) {
        if (jiraProjectKey == null || jiraProjectKey.isBlank()) {
            return Optional.empty();
        }
        return tenants.values().stream()
                .filter(t -> t.getJiraProjectKeys() != null
                        && t.getJiraProjectKeys().contains(jiraProjectKey))
                .findFirst();
    }
}
