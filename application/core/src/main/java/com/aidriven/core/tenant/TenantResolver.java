package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves the tenant from an incoming webhook or event payload.
 *
 * <p>Resolution strategies (in priority order):
 * <ol>
 *   <li>Explicit tenant ID in event headers/payload</li>
 *   <li>Jira project key → tenant mapping</li>
 *   <li>Webhook URL path parameter</li>
 *   <li>Default tenant fallback</li>
 * </ol>
 */
@Slf4j
public class TenantResolver {

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String TENANT_QUERY_PARAM = "tenantId";

    private final TenantRegistry registry;
    private final String defaultTenantId;

    public TenantResolver(TenantRegistry registry, String defaultTenantId) {
        this.registry = registry;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Resolves the tenant from an API Gateway event.
     *
     * @param event The Lambda event map (API Gateway format)
     * @return The resolved tenant configuration
     * @throws TenantNotFoundException if no tenant can be resolved
     */
    public TenantConfig resolve(Map<String, Object> event) {
        // 1. Check headers
        Optional<String> fromHeader = extractFromHeaders(event);
        if (fromHeader.isPresent()) {
            return lookupOrThrow(fromHeader.get());
        }

        // 2. Check query parameters
        Optional<String> fromQuery = extractFromQueryParams(event);
        if (fromQuery.isPresent()) {
            return lookupOrThrow(fromQuery.get());
        }

        // 3. Check path parameters
        Optional<String> fromPath = extractFromPathParams(event);
        if (fromPath.isPresent()) {
            return lookupOrThrow(fromPath.get());
        }

        // 4. Fall back to default tenant
        if (defaultTenantId != null && !defaultTenantId.isBlank()) {
            log.debug("Using default tenant: {}", defaultTenantId);
            return lookupOrThrow(defaultTenantId);
        }

        throw new TenantNotFoundException("Could not resolve tenant from event");
    }

    /**
     * Resolves tenant from a Jira project key.
     * Useful when a single tenant maps to one or more Jira projects.
     *
     * @param projectKey The Jira project key (e.g., "ACME")
     * @return Optional tenant config
     */
    public Optional<TenantConfig> resolveFromProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            return Optional.empty();
        }
        // Look for a tenant whose metadata contains this project key
        return registry.getAllTenants().stream()
                .filter(TenantConfig::isActive)
                .filter(t -> t.getMetadata() != null
                        && projectKey.equalsIgnoreCase(t.getMetadata().get("jiraProjectKey")))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> extractFromHeaders(Map<String, Object> event) {
        Object headers = event.get("headers");
        if (headers instanceof Map) {
            Map<String, String> headerMap = (Map<String, String>) headers;
            String tenantId = headerMap.get(TENANT_HEADER);
            if (tenantId == null) {
                tenantId = headerMap.get(TENANT_HEADER.toLowerCase());
            }
            return Optional.ofNullable(tenantId).filter(s -> !s.isBlank());
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> extractFromQueryParams(Map<String, Object> event) {
        Object params = event.get("queryStringParameters");
        if (params instanceof Map) {
            Map<String, String> paramMap = (Map<String, String>) params;
            return Optional.ofNullable(paramMap.get(TENANT_QUERY_PARAM))
                    .filter(s -> !s.isBlank());
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> extractFromPathParams(Map<String, Object> event) {
        Object params = event.get("pathParameters");
        if (params instanceof Map) {
            Map<String, String> paramMap = (Map<String, String>) params;
            return Optional.ofNullable(paramMap.get("tenantId"))
                    .filter(s -> !s.isBlank());
        }
        return Optional.empty();
    }

    private TenantConfig lookupOrThrow(String tenantId) {
        return registry.getTenant(tenantId)
                .filter(TenantConfig::isActive)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found or inactive: " + tenantId));
    }

    /**
     * Exception thrown when a tenant cannot be resolved.
     */
    public static class TenantNotFoundException extends RuntimeException {
        public TenantNotFoundException(String message) {
            super(message);
        }
    }
}
