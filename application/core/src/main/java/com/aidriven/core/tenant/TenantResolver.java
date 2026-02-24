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
 *   <li>Explicit label: {@code tenant:tenant-id}</li>
 *   <li>Project key mapping (e.g., "ACME" → "acme-corp")</li>
 *   <li>Default tenant (single-tenant mode)</li>
 * </ol>
 */
@Slf4j
public class TenantResolver {

    private static final String LABEL_PREFIX = "tenant:";

    private final TenantRegistry registry;
    private final String defaultTenantId;

    /**
     * @param registry        The tenant registry
     * @param defaultTenantId Fallback tenant ID (for single-tenant deployments)
     */
    public TenantResolver(TenantRegistry registry, String defaultTenantId) {
        this.registry = registry;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Resolves the tenant for a given ticket.
     *
     * @param ticket The Jira ticket
     * @return The resolved tenant config, or empty if no tenant found
     */
    public Optional<TenantConfig> resolve(TicketInfo ticket) {
        if (ticket == null) {
            return resolveDefault();
        }

        // 1. Check labels for explicit tenant
        Optional<TenantConfig> fromLabel = resolveFromLabels(ticket.getLabels());
        if (fromLabel.isPresent()) {
            log.debug("Resolved tenant from label for ticket {}: {}",
                    ticket.getTicketKey(), fromLabel.get().getTenantId());
            return fromLabel;
        }

        // 2. Check project key mapping
        Optional<TenantConfig> fromProject = resolveFromProjectKey(ticket.getProjectKey());
        if (fromProject.isPresent()) {
            log.debug("Resolved tenant from project key {} for ticket {}: {}",
                    ticket.getProjectKey(), ticket.getTicketKey(), fromProject.get().getTenantId());
            return fromProject;
        }

        // 3. Fall back to default
        return resolveDefault();
    }

    /**
     * Resolves tenant from Jira labels (e.g., "tenant:acme-corp").
     */
    Optional<TenantConfig> resolveFromLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return Optional.empty();
        }
        for (String label : labels) {
            String lower = label.toLowerCase().trim();
            if (lower.startsWith(LABEL_PREFIX)) {
                String tenantId = lower.substring(LABEL_PREFIX.length()).trim();
                Optional<TenantConfig> config = registry.getTenant(tenantId);
                if (config.isPresent()) {
                    return config;
                }
                log.warn("Tenant label '{}' found but tenant '{}' not registered", label, tenantId);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves tenant from Jira project key.
     * Looks for a tenant whose ID matches the project key (case-insensitive).
     */
    Optional<TenantConfig> resolveFromProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            return Optional.empty();
        }
        // Try exact match first (case-insensitive)
        return registry.getAllTenants().stream()
                .filter(t -> t.isActive())
                .filter(t -> projectKey.equalsIgnoreCase(t.getTenantId())
                        || projectKey.equalsIgnoreCase(t.getTenantName()))
                .findFirst();
    }

    /**
     * Returns the default tenant.
     */
    Optional<TenantConfig> resolveDefault() {
        if (defaultTenantId == null || defaultTenantId.isBlank()) {
            return Optional.empty();
        }
        return registry.getTenant(defaultTenantId);
    }
}
