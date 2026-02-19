package com.aidriven.contracts.config;

import java.util.Optional;

/**
 * Resolves the tenant configuration from request context.
 * <p>
 * Implementations can resolve tenants from:
 * <ul>
 *   <li>Jira project key prefix (e.g., "ACME-" → tenant "acme")</li>
 *   <li>API key / webhook secret</li>
 *   <li>Request headers</li>
 *   <li>Environment variables (single-tenant mode)</li>
 * </ul>
 * </p>
 */
public interface TenantResolver {

    /**
     * Resolves tenant configuration from a project key.
     *
     * @param projectKey The Jira project key (e.g., "ACME")
     * @return Tenant configuration, or empty if not found
     */
    Optional<TenantConfiguration> resolveByProjectKey(String projectKey);

    /**
     * Resolves tenant configuration from a tenant ID.
     *
     * @param tenantId The tenant identifier
     * @return Tenant configuration, or empty if not found
     */
    Optional<TenantConfiguration> resolveById(String tenantId);

    /**
     * Returns the default tenant configuration (for single-tenant mode).
     */
    TenantConfiguration getDefault();
}
