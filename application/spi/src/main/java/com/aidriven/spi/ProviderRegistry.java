package com.aidriven.spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for SPI provider implementations.
 * Supports multi-tenant resolution: each tenant can have different
 * provider bindings.
 *
 * <p>Usage:
 * <pre>{@code
 * ProviderRegistry registry = new ProviderRegistry();
 * registry.register(SourceControlProvider.class, "github", new GitHubProvider(...));
 * registry.register(SourceControlProvider.class, "bitbucket", new BitbucketProvider(...));
 *
 * // Bind tenant to specific providers
 * registry.bindTenant("acme-corp", SourceControlProvider.class, "github");
 *
 * // Resolve for tenant
 * SourceControlProvider scm = registry.resolve(SourceControlProvider.class, tenantContext);
 * }</pre>
 */
public class ProviderRegistry {

    /** providerId → provider instance, keyed by SPI interface */
    private final Map<Class<?>, Map<String, Object>> providers = new ConcurrentHashMap<>();

    /** tenantId → (SPI interface → providerId) */
    private final Map<String, Map<Class<?>, String>> tenantBindings = new ConcurrentHashMap<>();

    /** SPI interface → default providerId */
    private final Map<Class<?>, String> defaultBindings = new ConcurrentHashMap<>();

    /**
     * Registers a provider implementation.
     *
     * @param spiInterface The SPI interface class
     * @param providerId   Unique provider identifier
     * @param provider     The provider implementation
     */
    public <T> void register(Class<T> spiInterface, String providerId, T provider) {
        Objects.requireNonNull(spiInterface, "spiInterface");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(provider, "provider");

        providers.computeIfAbsent(spiInterface, k -> new ConcurrentHashMap<>())
                .put(providerId, provider);
    }

    /**
     * Sets the default provider for an SPI interface.
     */
    public <T> void setDefault(Class<T> spiInterface, String providerId) {
        defaultBindings.put(spiInterface, providerId);
    }

    /**
     * Binds a tenant to a specific provider for an SPI interface.
     */
    public void bindTenant(String tenantId, Class<?> spiInterface, String providerId) {
        tenantBindings.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
                .put(spiInterface, providerId);
    }

    /**
     * Resolves the provider for a tenant context.
     * Resolution order:
     * 1. Tenant-specific binding
     * 2. Tenant configuration (key: spi interface simple name lowercase)
     * 3. Default binding
     * 4. First registered provider
     *
     * @throws IllegalStateException if no provider can be resolved
     */
    @SuppressWarnings("unchecked")
    public <T> T resolve(Class<T> spiInterface, TenantContext tenant) {
        Map<String, Object> registered = providers.get(spiInterface);
        if (registered == null || registered.isEmpty()) {
            throw new IllegalStateException(
                    "No providers registered for " + spiInterface.getSimpleName());
        }

        // 1. Tenant-specific binding
        String tenantId = tenant != null ? tenant.tenantId() : "default";
        Map<Class<?>, String> bindings = tenantBindings.get(tenantId);
        if (bindings != null) {
            String providerId = bindings.get(spiInterface);
            if (providerId != null && registered.containsKey(providerId)) {
                return (T) registered.get(providerId);
            }
        }

        // 2. Tenant configuration
        if (tenant != null) {
            String configKey = spiInterface.getSimpleName().toLowerCase();
            String configProviderId = tenant.getConfig(configKey);
            if (configProviderId != null && registered.containsKey(configProviderId)) {
                return (T) registered.get(configProviderId);
            }
        }

        // 3. Default binding
        String defaultId = defaultBindings.get(spiInterface);
        if (defaultId != null && registered.containsKey(defaultId)) {
            return (T) registered.get(defaultId);
        }

        // 4. First registered
        return (T) registered.values().iterator().next();
    }

    /**
     * Resolves using the default tenant context.
     */
    public <T> T resolve(Class<T> spiInterface) {
        return resolve(spiInterface, TenantContext.defaultTenant());
    }

    /**
     * Returns all registered provider IDs for an SPI interface.
     */
    public Set<String> getRegisteredProviders(Class<?> spiInterface) {
        Map<String, Object> registered = providers.get(spiInterface);
        return registered != null ? Collections.unmodifiableSet(registered.keySet()) : Set.of();
    }

    /**
     * Checks if a provider is registered for the given SPI interface.
     */
    public boolean hasProvider(Class<?> spiInterface, String providerId) {
        Map<String, Object> registered = providers.get(spiInterface);
        return registered != null && registered.containsKey(providerId);
    }

    /**
     * Returns the number of registered providers across all interfaces.
     */
    public int totalProviderCount() {
        return providers.values().stream()
                .mapToInt(Map::size)
                .sum();
    }
}
