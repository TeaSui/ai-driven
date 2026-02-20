package com.aidriven.spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for discovering and managing pluggable service modules.
 * Uses {@link java.util.ServiceLoader} for module discovery and provides
 * lookup by ID and category.
 *
 * <p>Thread-safe. Designed for use in Lambda environments where
 * the registry is populated on cold start and reused across invocations.</p>
 */
public final class ModuleRegistry {

    private final Map<String, ServiceDescriptor> descriptorsById = new ConcurrentHashMap<>();
    private final Map<ServiceCategory, List<ServiceDescriptor>> descriptorsByCategory = new ConcurrentHashMap<>();

    /**
     * Creates a registry and discovers all modules on the classpath.
     */
    public ModuleRegistry() {
        discoverModules();
    }

    /**
     * Discovers all {@link ServiceDescriptor} implementations via ServiceLoader.
     */
    private void discoverModules() {
        ServiceLoader<ServiceDescriptor> loader = ServiceLoader.load(ServiceDescriptor.class);
        for (ServiceDescriptor descriptor : loader) {
            register(descriptor);
        }
    }

    /**
     * Manually registers a service descriptor.
     * Useful for testing or programmatic registration.
     */
    public void register(ServiceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(descriptor.id(), "descriptor id must not be null");

        descriptorsById.put(descriptor.id(), descriptor);
        descriptorsByCategory
                .computeIfAbsent(descriptor.category(), k -> new ArrayList<>())
                .add(descriptor);
    }

    /**
     * Looks up a module descriptor by ID.
     */
    public Optional<ServiceDescriptor> findById(String id) {
        return Optional.ofNullable(descriptorsById.get(id));
    }

    /**
     * Returns all modules in a given category.
     */
    public List<ServiceDescriptor> findByCategory(ServiceCategory category) {
        return descriptorsByCategory.getOrDefault(category, List.of());
    }

    /**
     * Returns all registered module IDs.
     */
    public Set<String> registeredIds() {
        return Collections.unmodifiableSet(descriptorsById.keySet());
    }

    /**
     * Returns the total number of registered modules.
     */
    public int size() {
        return descriptorsById.size();
    }

    /**
     * Validates that a tenant's configuration satisfies all required keys
     * for a given set of module IDs.
     *
     * @param moduleIds The modules the tenant wants to use
     * @param tenantConfig The tenant's configuration
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateTenantConfig(Set<String> moduleIds, Map<String, String> tenantConfig) {
        List<String> errors = new ArrayList<>();

        for (String moduleId : moduleIds) {
            ServiceDescriptor descriptor = descriptorsById.get(moduleId);
            if (descriptor == null) {
                errors.add("Unknown module: " + moduleId);
                continue;
            }

            for (String requiredKey : descriptor.requiredConfigKeys()) {
                String value = tenantConfig.get(requiredKey);
                if (value == null || value.isBlank()) {
                    errors.add(String.format("Module '%s' requires config key '%s'", moduleId, requiredKey));
                }
            }

            // Check dependencies
            for (String depId : descriptor.dependencies()) {
                if (!moduleIds.contains(depId)) {
                    errors.add(String.format("Module '%s' depends on '%s' which is not included", moduleId, depId));
                }
            }
        }

        return errors;
    }

    @Override
    public String toString() {
        return "ModuleRegistry{modules=" + descriptorsById.keySet() + "}";
    }
}
