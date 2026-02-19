package com.aidriven.core.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Resolves service providers based on the current tenant context.
 *
 * <p>This is the bridge between tenant configuration and the provider registry.
 * It reads the tenant's preferences and selects the appropriate provider.</p>
 *
 * <h3>Resolution order:</h3>
 * <ol>
 *   <li>Tenant preference for the service type (e.g., tenant prefers "github")</li>
 *   <li>Explicit qualifier override (e.g., from Jira label)</li>
 *   <li>Registry default for the type</li>
 * </ol>
 */
@Slf4j
public class TenantAwareProviderResolver {

    private final ServiceProviderRegistry registry;

    public TenantAwareProviderResolver(ServiceProviderRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Resolves a provider for the current tenant.
     *
     * @param type          The service interface type
     * @param preferenceKey The tenant preference key (e.g., "source_control")
     * @return The resolved provider
     */
    public <T> T resolve(Class<T> type, String preferenceKey) {
        TenantContext ctx = TenantContext.current();

        // 1. Check tenant preference
        return ctx.getPreference(preferenceKey)
                .filter(q -> registry.isRegistered(type, q))
                .map(q -> {
                    log.debug("Resolved {} via tenant preference: {} [{}]",
                            type.getSimpleName(), ctx.getTenantId(), q);
                    return registry.get(type, q);
                })
                .orElseGet(() -> {
                    // 2. Fall back to registry default
                    log.debug("Using default {} for tenant {}",
                            type.getSimpleName(), ctx.getTenantId());
                    return registry.getDefault(type);
                });
    }

    /**
     * Resolves a provider with an explicit qualifier override.
     * The override takes precedence over tenant preferences.
     *
     * @param type              The service interface type
     * @param preferenceKey     The tenant preference key
     * @param qualifierOverride Explicit qualifier (e.g., from Jira label), may be null
     * @return The resolved provider
     */
    public <T> T resolve(Class<T> type, String preferenceKey, String qualifierOverride) {
        // Explicit override takes highest priority
        if (qualifierOverride != null && !qualifierOverride.isBlank()
                && registry.isRegistered(type, qualifierOverride)) {
            log.debug("Resolved {} via explicit override: {}", type.getSimpleName(), qualifierOverride);
            return registry.get(type, qualifierOverride);
        }

        return resolve(type, preferenceKey);
    }

    /**
     * Returns the underlying registry.
     */
    public ServiceProviderRegistry getRegistry() {
        return registry;
    }
}
