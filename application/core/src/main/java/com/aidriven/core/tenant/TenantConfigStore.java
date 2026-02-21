package com.aidriven.core.tenant;

import java.util.Optional;

/**
 * Abstraction for tenant configuration storage.
 * Implementations can use DynamoDB, S3, or any other backing store.
 */
public interface TenantConfigStore {

    /**
     * Retrieves the raw JSON configuration for a tenant.
     *
     * @param tenantId Tenant identifier
     * @return JSON config string, or empty if not found
     */
    Optional<String> getTenantConfig(String tenantId);

    /**
     * Stores or updates a tenant configuration.
     *
     * @param tenantId   Tenant identifier
     * @param configJson JSON configuration string
     */
    void saveTenantConfig(String tenantId, String configJson);

    /**
     * Deletes a tenant configuration.
     *
     * @param tenantId Tenant identifier
     */
    void deleteTenantConfig(String tenantId);
}