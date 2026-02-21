package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Resolves the tenant ID from incoming webhook requests.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>HTTP header: {@code X-Tenant-Id}</li>
 *   <li>Query parameter: {@code tenantId}</li>
 *   <li>Jira project key prefix mapping (e.g., "ACME-123" → "acme-corp")</li>
 *   <li>Default tenant (single-tenant mode)</li>
 * </ol>
 */
@Slf4j
public class TenantResolver {

    private final Map<String, String> projectKeyToTenantMap;

    /**
     * Creates a resolver with no project key mappings (single-tenant mode).
     */
    public TenantResolver() {
        this(Map.of());
    }

    /**
     * Creates a resolver with project key → tenant ID mappings.
     *
     * @param projectKeyToTenantMap Map of Jira project key prefix → tenant ID
     *                              e.g., {"ACME" → "acme-corp", "BETA" → "beta-inc"}
     */
    public TenantResolver(Map<String, String> projectKeyToTenantMap) {
        this.projectKeyToTenantMap = Map.copyOf(projectKeyToTenantMap);
    }

    /**
     * Resolves the tenant ID from an API Gateway Lambda event.
     *
     * @param event The Lambda event map (API Gateway format)
     * @return The resolved tenant ID, or {@link TenantContext#DEFAULT_TENANT}
     */
    @SuppressWarnings("unchecked")
    public String resolveFromEvent(Map<String, Object> event) {
        // 1. Check HTTP headers
        Object headersObj = event.get("headers");
        if (headersObj instanceof Map<?, ?> headers) {
            String tenantId = (String) ((Map<String, Object>) headers)
                    .get(TenantContext.TENANT_HEADER);
            if (tenantId != null && !tenantId.isBlank()) {
                log.debug("Resolved tenant from header: {}", tenantId);
                return tenantId;
            }
            // Case-insensitive header check
            tenantId = ((Map<String, Object>) headers).entrySet().stream()
                    .filter(e -> TenantContext.TENANT_HEADER.equalsIgnoreCase(e.getKey()))
                    .map(e -> (String) e.getValue())
                    .findFirst()
                    .orElse(null);
            if (tenantId != null && !tenantId.isBlank()) {
                log.debug("Resolved tenant from header (case-insensitive): {}", tenantId);
                return tenantId;
            }
        }

        // 2. Check query parameters
        Object queryParamsObj = event.get("queryStringParameters");
        if (queryParamsObj instanceof Map<?, ?> queryParams) {
            String tenantId = (String) ((Map<String, Object>) queryParams)
                    .get(TenantContext.TENANT_QUERY_PARAM);
            if (tenantId != null && !tenantId.isBlank()) {
                log.debug("Resolved tenant from query param: {}", tenantId);
                return tenantId;
            }
        }

        // 3. Default tenant
        log.debug("No tenant found in event, using default tenant");
        return TenantContext.DEFAULT_TENANT;
    }

    /**
     * Resolves the tenant ID from a Jira ticket key.
     *
     * @param ticketKey The Jira ticket key (e.g., "ACME-123")
     * @return The tenant ID mapped to this project, or {@link TenantContext#DEFAULT_TENANT}
     */
    public String resolveFromTicketKey(String ticketKey) {
        if (ticketKey == null || !ticketKey.contains("-")) {
            return TenantContext.DEFAULT_TENANT;
        }
        String projectKey = ticketKey.split("-")[0];
        String tenantId = projectKeyToTenantMap.get(projectKey);
        if (tenantId != null) {
            log.debug("Resolved tenant from project key {}: {}", projectKey, tenantId);
            return tenantId;
        }
        return TenantContext.DEFAULT_TENANT;
    }
}
