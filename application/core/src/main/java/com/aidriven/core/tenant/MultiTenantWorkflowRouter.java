package com.aidriven.core.tenant;

import com.aidriven.core.model.TicketInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Routes workflow requests to the correct tenant configuration.
 *
 * <p>Resolves the tenant from a Jira ticket's project key or labels,
 * then sets the tenant context for downstream processing.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>Explicit label: {@code tenant:acme-corp}</li>
 *   <li>Project key mapping (e.g., "ACME" → "acme-corp")</li>
 *   <li>Default tenant (single-tenant mode)</li>
 * </ol>
 */
@Slf4j
public class MultiTenantWorkflowRouter {

    private static final String TENANT_LABEL_PREFIX = "tenant:";

    private final TenantRegistry tenantRegistry;
    private final String defaultTenantId;

    /**
     * Creates a router with a tenant registry and optional default tenant.
     *
     * @param tenantRegistry  Registry of all tenant configurations
     * @param defaultTenantId Default tenant ID for single-tenant mode (may be null)
     */
    public MultiTenantWorkflowRouter(TenantRegistry tenantRegistry, String defaultTenantId) {
        this.tenantRegistry = tenantRegistry;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Resolves the tenant for a given ticket and sets the thread-local context.
     *
     * @param ticket The Jira ticket to route
     * @return The resolved tenant configuration
     * @throws TenantRegistry.TenantNotFoundException if no tenant can be resolved
     */
    public TenantConfig resolveAndSetContext(TicketInfo ticket) {
        TenantConfig config = resolve(ticket);
        TenantContext.set(config);
        log.info("Resolved tenant '{}' for ticket {}", config.getTenantId(), ticket.getTicketKey());
        return config;
    }

    /**
     * Resolves the tenant for a given ticket without setting context.
     *
     * @param ticket The Jira ticket
     * @return The resolved tenant configuration
     */
    public TenantConfig resolve(TicketInfo ticket) {
        // 1. Check for explicit tenant label
        Optional<TenantConfig> fromLabel = resolveFromLabels(ticket);
        if (fromLabel.isPresent()) {
            return fromLabel.get();
        }

        // 2. Check project key mapping
        Optional<TenantConfig> fromProject = resolveFromProjectKey(ticket);
        if (fromProject.isPresent()) {
            return fromProject.get();
        }

        // 3. Fall back to default tenant
        if (defaultTenantId != null && !defaultTenantId.isBlank()) {
            return tenantRegistry.getOrThrow(defaultTenantId);
        }

        // 4. If only one tenant registered, use it
        if (tenantRegistry.size() == 1) {
            return tenantRegistry.getAllTenants().iterator().next();
        }

        throw new TenantRegistry.TenantNotFoundException(
                "Cannot resolve tenant for ticket: " + ticket.getTicketKey());
    }

    /**
     * Resolves tenant from Jira labels (e.g., "tenant:acme-corp").
     */
    private Optional<TenantConfig> resolveFromLabels(TicketInfo ticket) {
        if (ticket.getLabels() == null) return Optional.empty();

        return ticket.getLabels().stream()
                .filter(l -> l.toLowerCase().startsWith(TENANT_LABEL_PREFIX))
                .map(l -> l.substring(TENANT_LABEL_PREFIX.length()).trim())
                .filter(id -> !id.isBlank())
                .map(tenantRegistry::find)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /**
     * Resolves tenant from the ticket's project key.
     * Looks for a tenant whose ID matches the project key (case-insensitive).
     */
    private Optional<TenantConfig> resolveFromProjectKey(TicketInfo ticket) {
        if (ticket.getProjectKey() == null) return Optional.empty();

        String projectKey = ticket.getProjectKey().toLowerCase();
        return tenantRegistry.getAllTenants().stream()
                .filter(t -> t.getTenantId() != null &&
                        t.getTenantId().toLowerCase().contains(projectKey))
                .findFirst();
    }
}
