package com.aidriven.core.tenant;

import com.aidriven.core.model.TicketInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Resolves the tenant for a given request context.
 * Uses multiple strategies to identify the tenant:
 * <ol>
 *   <li>Explicit tenant label on the Jira ticket (e.g., "tenant:acme-corp")</li>
 *   <li>Jira project key mapping in the registry</li>
 *   <li>Default tenant fallback</li>
 * </ol>
 */
@Slf4j
public class TenantResolver {

    private static final String TENANT_LABEL_PREFIX = "tenant:";

    private final TenantRegistry registry;
    private final String defaultTenantId;

    public TenantResolver(TenantRegistry registry, String defaultTenantId) {
        this.registry = registry;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Resolves the tenant for a given ticket.
     *
     * @param ticket The Jira ticket info
     * @return The resolved tenant config, or empty if no tenant found
     */
    public Optional<TenantConfig> resolve(TicketInfo ticket) {
        if (ticket == null) {
            return resolveDefault();
        }

        // 1. Check for explicit tenant label
        if (ticket.getLabels() != null) {
            for (String label : ticket.getLabels()) {
                if (label != null && label.toLowerCase().startsWith(TENANT_LABEL_PREFIX)) {
                    String tenantId = label.substring(TENANT_LABEL_PREFIX.length()).trim();
                    Optional<TenantConfig> fromLabel = registry.getTenant(tenantId);
                    if (fromLabel.isPresent()) {
                        log.debug("Resolved tenant '{}' from label for ticket {}",
                                tenantId, ticket.getTicketKey());
                        return fromLabel;
                    }
                    log.warn("Tenant label '{}' found but tenant not registered", tenantId);
                }
            }
        }

        // 2. Resolve by Jira project key
        if (ticket.getProjectKey() != null) {
            Optional<TenantConfig> fromProject = registry.resolveByJiraProject(ticket.getProjectKey());
            if (fromProject.isPresent()) {
                log.debug("Resolved tenant '{}' from project key '{}' for ticket {}",
                        fromProject.get().getTenantId(), ticket.getProjectKey(), ticket.getTicketKey());
                return fromProject;
            }
        }

        // 3. Default fallback
        return resolveDefault();
    }

    /**
     * Resolves the tenant by explicit tenant ID.
     *
     * @param tenantId The tenant identifier
     * @return The tenant config, or empty if not found
     */
    public Optional<TenantConfig> resolveById(String tenantId) {
        return registry.getTenant(tenantId);
    }

    private Optional<TenantConfig> resolveDefault() {
        if (defaultTenantId != null && !defaultTenantId.isBlank()) {
            return registry.getTenant(defaultTenantId);
        }
        return Optional.empty();
    }
}
