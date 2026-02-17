package com.aidriven.platform;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry of all available modules in the platform.
 * Modules self-register during application startup.
 *
 * <p>The registry supports:
 * <ul>
 *   <li>Registration of module descriptors</li>
 *   <li>Querying modules by type</li>
 *   <li>Validation of tenant configurations against required keys</li>
 * </ul>
 */
@Slf4j
public class ModuleRegistry {

    private final Map<String, ModuleDescriptor> modules = new LinkedHashMap<>();

    /**
     * Registers a module descriptor.
     *
     * @param descriptor The module to register
     * @throws IllegalArgumentException if a module with the same ID is already registered
     */
    public void register(ModuleDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (modules.containsKey(descriptor.moduleId())) {
            throw new IllegalArgumentException(
                    "Module already registered: " + descriptor.moduleId());
        }
        modules.put(descriptor.moduleId(), descriptor);
        log.info("Registered module: {} ({})", descriptor.moduleId(), descriptor.type());
    }

    /**
     * Gets a module by ID.
     *
     * @param moduleId The module identifier
     * @return The module descriptor, or empty if not found
     */
    public Optional<ModuleDescriptor> get(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    /**
     * Lists all registered modules.
     */
    public List<ModuleDescriptor> getAll() {
        return List.copyOf(modules.values());
    }

    /**
     * Lists modules filtered by type.
     */
    public List<ModuleDescriptor> getByType(ModuleDescriptor.ModuleType type) {
        return modules.values().stream()
                .filter(m -> m.type() == type)
                .collect(Collectors.toList());
    }

    /**
     * Validates that a tenant's configuration contains all required keys
     * for the specified modules.
     *
     * @param moduleIds    The modules the tenant wants to use
     * @param tenantConfig The tenant's configuration
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateTenantConfig(List<String> moduleIds, Map<String, String> tenantConfig) {
        List<String> errors = new ArrayList<>();

        for (String moduleId : moduleIds) {
            ModuleDescriptor descriptor = modules.get(moduleId);
            if (descriptor == null) {
                errors.add("Unknown module: " + moduleId);
                continue;
            }

            for (String requiredKey : descriptor.requiredConfigKeys()) {
                if (!tenantConfig.containsKey(requiredKey)
                        || tenantConfig.get(requiredKey) == null
                        || tenantConfig.get(requiredKey).isBlank()) {
                    errors.add(String.format("Module '%s' requires config key '%s'",
                            moduleId, requiredKey));
                }
            }
        }

        return errors;
    }

    /**
     * Returns the number of registered modules.
     */
    public int size() {
        return modules.size();
    }

    /**
     * Returns the set of registered module IDs.
     */
    public Set<String> getRegisteredIds() {
        return Collections.unmodifiableSet(modules.keySet());
    }
}
