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
 *   <li>Explicit tenant label on ticket (e.g., "tenant:acme-corp")</li>
 *   <li>Project key mapping (e.g., ACME-* → acme-corp)</li>
 *   <li>Default tenant (single-tenant deployments)</li>
 * </ol>
 */
@Slf4j
public class TenantResolver {

    private static final String TENANT_LABEL_PREFIX = "tenant:";

    private final TenantRegistry registry;
    private final String defaultTenantId;

    /**
     * Creates a TenantResolver with a registry and default tenant.
     *
     * @param registry        The tenant registry
     * @param defaultTenantId The default tenant ID for single-tenant deployments
     */
    public TenantResolver(TenantRegistry registry, String defaultTenantId) {
        this.registry = registry;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Resolves the tenant for a given ticket.
     *
     * @param ticket The Jira ticket
     * @return The resolved tenant configuration
     * @throws TenantRegistry.TenantNotFoundException if no tenant can be resolved
     */
    public TenantConfig resolve(TicketInfo ticket) {
        // 1. Check for explicit tenant label
        Optional<TenantConfig> fromLabel = resolveFromLabels(ticket.getLabels());
        if (fromLabel.isPresent()) {
            log.info("Resolved tenant '{}' from label for ticket {}",
                    fromLabel.get().getTenantId(), ticket.getTicketKey());
            return fromLabel.get();
        }

        // 2. Check project key mapping
        Optional<TenantConfig> fromProject = resolveFromProjectKey(ticket.getProjectKey());
        if (fromProject.isPresent()) {
            log.info("Resolved tenant '{}' from project key '{}' for ticket {}",
                    fromProject.get().getTenantId(), ticket.getProjectKey(), ticket.getTicketKey());
            return fromProject.get();
        }

        // 3. Fall back to default tenant
        if (defaultTenantId != null && !defaultTenantId.isBlank()) {
            log.info("Using default tenant '{}' for ticket {}", defaultTenantId, ticket.getTicketKey());
            return registry.getRequiredTenant(defaultTenantId);
        }

        throw new TenantRegistry.TenantNotFoundException(
                "Could not resolve tenant for ticket: " + ticket.getTicketKey());
    }

    /**
     * Resolves tenant from Jira labels (e.g., "tenant:acme-corp").
     */
    private Optional<TenantConfig> resolveFromLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return Optional.empty();
        }
        for (String label : labels) {
            String lower = label.toLowerCase().trim();
            if (lower.startsWith(TENANT_LABEL_PREFIX)) {
                String tenantId = lower.substring(TENANT_LABEL_PREFIX.length());
                Optional<TenantConfig> tenant = registry.getTenant(tenantId);
                if (tenant.isPresent() && tenant.get().isActive()) {
                    return tenant;
                }
                log.warn("Tenant '{}' from label not found or inactive", tenantId);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves tenant from Jira project key.
     * Looks for a tenant whose ID matches the project key prefix (case-insensitive).
     */
    private Optional<TenantConfig> resolveFromProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            return Optional.empty();
        }
        return registry.getAllTenants().stream()
                .filter(TenantConfig::isActive)
                .filter(t -> projectKey.equalsIgnoreCase(t.getTenantId())
                        || projectKey.toLowerCase().startsWith(t.getTenantId().toLowerCase()))
                .findFirst();
    }
}
