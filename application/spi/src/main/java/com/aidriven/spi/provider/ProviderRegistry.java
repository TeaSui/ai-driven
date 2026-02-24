package com.aidriven.spi.provider;

import com.aidriven.spi.model.OperationContext;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Registry for managing and resolving SPI providers.
 * Supports both manual registration and ServiceLoader discovery.
 */
@Slf4j
public class ProviderRegistry {
    private final Map<Class<?>, Map<String, Object>> providers = new ConcurrentHashMap<>();

    public <T> void register(Class<T> type, String name, T provider) {
        log.info("Registering provider {}: {}", type.getSimpleName(), name);
        providers.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(name, provider);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> resolve(Class<T> type, String name) {
        Map<String, Object> typeProviders = providers.get(type);
        if (typeProviders != null && typeProviders.containsKey(name)) {
            return Optional.ofNullable((T) typeProviders.get(name));
        }

        log.debug("Provider not found in manual registry, searching via ServiceLoader: {}:{}", type.getSimpleName(),
                name);
        return ServiceLoader.load(type).stream()
                .map(ServiceLoader.Provider::get)
                .findFirst();
    }

    /**
     * Helper to resolve an issue tracker provider by name (e.g., "jira").
     */
    public Optional<IssueTrackerProvider> resolveIssueTracker(OperationContext context, String name) {
        return resolve(IssueTrackerProvider.class, name);
    }

    /**
     * Helper to resolve a source control provider that supports the given URI.
     */
    @SuppressWarnings("unchecked")
    public Optional<SourceControlProvider> resolveSourceControl(OperationContext context, String repositoryUri) {
        // First check manual registry
        Map<String, Object> scProviders = providers.get(SourceControlProvider.class);
        if (scProviders != null) {
            for (Object p : scProviders.values()) {
                SourceControlProvider provider = (SourceControlProvider) p;
                if (provider.supports(repositoryUri)) {
                    return Optional.of(provider);
                }
            }
        }

        // Fallback to ServiceLoader
        return ServiceLoader.load(SourceControlProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.supports(repositoryUri))
                .findFirst();
    }

    public <T> T resolveRequired(Class<T> type, String name) {
        return resolve(type, name)
                .orElseThrow(() -> new RuntimeException("Provider not found: " + type.getSimpleName() + ":" + name));
    }
}
