package com.aidriven.core.config;

import com.aidriven.spi.TenantContext;

import java.util.Optional;

/**
 * Interface for resolving tenant configuration from various sources.
 *
 * <p>Implementations can load tenant config from:
 * <ul>
 *   <li>DynamoDB (production)</li>
 *   <li>Environment variables (single-tenant / legacy)</li>
 *   <li>JSON files (testing)</li>
 *   <li>AWS AppConfig (future)</li>
 * </ul>
 */
public interface TenantConfigProvider {

    /**
     * Resolves tenant context by tenant ID.
     *
     * @param tenantId The tenant identifier
     * @return Tenant context if found, empty otherwise
     */
    Optional<TenantContext> resolve(String tenantId);

    /**
     * Returns the default tenant context (for single-tenant / legacy mode).
     * This is used when no explicit tenant ID is provided.
     *
     * @return Default tenant context
     */
    TenantContext getDefault();
}
