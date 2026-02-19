package com.aidriven.core.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry for dynamically discovering and managing service providers.
 *
 * <p>Enables a plugin-based architecture where each integration module
 * (Jira, Bitbucket, GitHub, Claude, etc.) registers itself as a provider.
 * Consumers look up providers by type and optional qualifier.</p>
 *
 * <p>This replaces the hard-coded ServiceFactory wiring with a flexible
 * registry that supports:</p>
 * <ul>
 *   <li>Multiple implementations of the same interface (e.g., Bitbucket + GitHub)</li>
 *   <li>Tenant-specific provider selection</li>
 *   <li>Runtime registration/deregistration for testing</li>
 *   <li>Java SPI auto-discovery via ServiceLoader</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // Register
 * registry.register(SourceControlClient.class, "bitbucket", bitbucketClient);
 * registry.register(SourceControlClient.class, "github", githubClient);
 *
 * // Lookup
 * SourceControlClient client = registry.get(SourceControlClient.class, "github");
 *
 * // List all
 * List<SourceControlClient> all = registry.getAll(SourceControlClient.class);
 * }</pre>
 */
@Slf4j
public class ServiceProviderRegistry {

    private final Map<ProviderKey, Object> providers = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> defaultQualifiers = new ConcurrentHashMap<>();

    /**
     * Registers a service provider with a qualifier.
     *
     * @param type      The service interface type
     * @param qualifier A unique qualifier (e.g., "bitbucket", "github", "jira")
     * @param instance  The provider instance
     * @param <T>       The service type
     */
    public <T> void register(Class<T> type, String qualifier, T instance) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(qualifier, "qualifier must not be null");
        Objects.requireNonNull(instance, "instance must not be null");

        ProviderKey key = new ProviderKey(type, qualifier.toLowerCase());
        Object previous = providers.put(key, instance);

        if (previous != null) {
            log.info("Replaced provider: {} [{}]", type.getSimpleName(), qualifier);
        } else {
            log.info("Registered provider: {} [{}]", type.getSimpleName(), qualifier);
        }
    }

    /**
     * Registers a provider as the default for its type.
     */
    public <T> void registerDefault(Class<T> type, String qualifier, T instance) {
        register(type, qualifier, instance);
        setDefault(type, qualifier);
    }

    /**
     * Sets the default qualifier for a service type.
     */
    public <T> void setDefault(Class<T> type, String qualifier) {
        defaultQualifiers.put(type, qualifier.toLowerCase());
        log.info("Set default provider for {}: {}", type.getSimpleName(), qualifier);
    }

    /**
     * Gets a provider by type and qualifier.
     *
     * @param type      The service interface type
     * @param qualifier The qualifier
     * @return The provider instance
     * @throws NoSuchElementException if no provider is registered
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type, String qualifier) {
        ProviderKey key = new ProviderKey(type, qualifier.toLowerCase());
        Object instance = providers.get(key);
        if (instance == null) {
            throw new NoSuchElementException(
                    String.format("No provider registered for %s [%s]. Available: %s",
                            type.getSimpleName(), qualifier, getQualifiers(type)));
        }
        return (T) instance;
    }

    /**
     * Gets the default provider for a type.
     *
     * @param type The service interface type
     * @return The default provider
     * @throws NoSuchElementException if no default is set or no provider exists
     */
    public <T> T getDefault(Class<T> type) {
        String qualifier = defaultQualifiers.get(type);
        if (qualifier == null) {
            // If only one provider exists, use it as default
            List<T> all = getAll(type);
            if (all.size() == 1) {
                return all.get(0);
            }
            throw new NoSuchElementException(
                    String.format("No default provider set for %s. Available: %s",
                            type.getSimpleName(), getQualifiers(type)));
        }
        return get(type, qualifier);
    }

    /**
     * Gets a provider by type and qualifier, or empty if not registered.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> type, String qualifier) {
        ProviderKey key = new ProviderKey(type, qualifier.toLowerCase());
        return Optional.ofNullable((T) providers.get(key));
    }

    /**
     * Gets all providers of a given type.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getAll(Class<T> type) {
        return providers.entrySet().stream()
                .filter(e -> e.getKey().type().equals(type))
                .map(e -> (T) e.getValue())
                .collect(Collectors.toList());
    }

    /**
     * Gets all registered qualifiers for a type.
     */
    public Set<String> getQualifiers(Class<?> type) {
        return providers.keySet().stream()
                .filter(k -> k.type().equals(type))
                .map(ProviderKey::qualifier)
                .collect(Collectors.toSet());
    }

    /**
     * Checks if a provider is registered.
     */
    public boolean isRegistered(Class<?> type, String qualifier) {
        return providers.containsKey(new ProviderKey(type, qualifier.toLowerCase()));
    }

    /**
     * Removes a provider.
     */
    public <T> void deregister(Class<T> type, String qualifier) {
        ProviderKey key = new ProviderKey(type, qualifier.toLowerCase());
        Object removed = providers.remove(key);
        if (removed != null) {
            log.info("Deregistered provider: {} [{}]", type.getSimpleName(), qualifier);
        }
    }

    /**
     * Removes all providers. Useful for test cleanup.
     */
    public void clear() {
        providers.clear();
        defaultQualifiers.clear();
        log.info("Cleared all providers");
    }

    /**
     * Returns the total number of registered providers.
     */
    public int size() {
        return providers.size();
    }

    /**
     * Composite key for provider lookup.
     */
    private record ProviderKey(Class<?> type, String qualifier) {
    }
}
