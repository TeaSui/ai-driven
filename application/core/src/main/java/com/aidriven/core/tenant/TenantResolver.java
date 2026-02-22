package com.aidriven.core.tenant;

import com.aidriven.core.model.TicketInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the tenant for a given request context.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>Jira ticket label: {@code tenant:acme}</li>
 *   <li>Project key prefix mapping (e.g., "ACME" → "acme" tenant)</li>
 *   <li>Default tenant from environment</li>
 * </ol>
 */
@Slf4j
public final class TenantResolver {

    private static final String LABEL_PREFIX = "tenant:";

    private TenantResolver() {}

    /**
     * Resolves the tenant ID from a ticket's labels and project key.
     *
     * @param ticket          The Jira ticket
     * @param defaultTenantId Fallback tenant ID (may be null)
     * @return The resolved tenant ID, or null if none found
     */
    public static String resolve(TicketInfo ticket, String defaultTenantId) {
        if (ticket == null) {
            return defaultTenantId;
        }

        // 1. Check labels for explicit tenant declaration
        String fromLabel = resolveFromLabels(ticket.getLabels());
        if (fromLabel != null) {
            log.debug("Resolved tenant '{}' from label for ticket {}", fromLabel, ticket.getTicketKey());
            return fromLabel;
        }

        // 2. Derive from project key (e.g., "ACME-123" → "acme")
        String fromProjectKey = resolveFromProjectKey(ticket.getProjectKey());
        if (fromProjectKey != null) {
            log.debug("Resolved tenant '{}' from project key for ticket {}", fromProjectKey, ticket.getTicketKey());
            return fromProjectKey;
        }

        // 3. Fall back to default
        return defaultTenantId;
    }

    /**
     * Extracts tenant ID from Jira labels (e.g., "tenant:acme" → "acme").
     */
    public static String resolveFromLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        for (String label : labels) {
            String lower = label.toLowerCase().trim();
            if (lower.startsWith(LABEL_PREFIX)) {
                String tenantId = lower.substring(LABEL_PREFIX.length()).trim();
                if (!tenantId.isBlank()) {
                    return tenantId;
                }
            }
        }
        return null;
    }

    /**
     * Derives a tenant ID from a Jira project key by lowercasing it.
     * Returns null if the project key is null or blank.
     */
    public static String resolveFromProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            return null;
        }
        return projectKey.toLowerCase();
    }
}
