package com.aidriven.core.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Discovers, validates, and initializes modules.
 *
 * <p>Supports two discovery mechanisms:</p>
 * <ul>
 *   <li><b>Java SPI</b>: Automatic discovery via {@code META-INF/services}</li>
 *   <li><b>Programmatic</b>: Manual registration for testing or custom setups</li>
 * </ul>
 *
 * <p>Initialization order is determined by {@link ModuleDescriptor#priority()}.
 * Modules with missing required config keys are skipped with a warning.</p>
 */
@Slf4j
public class ModuleLoader {

    private final ServiceProviderRegistry registry;
    private final List<ModuleDescriptor> loadedModules = new ArrayList<>();

    public ModuleLoader(ServiceProviderRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Discovers modules via Java SPI and initializes them.
     *
     * @param config Configuration map for all modules
     * @return Number of successfully initialized modules
     */
    public int discoverAndInitialize(Map<String, String> config) {
        ServiceLoader<ModuleDescriptor> loader = ServiceLoader.load(ModuleDescriptor.class);
        List<ModuleDescriptor> discovered = new ArrayList<>();
        loader.forEach(discovered::add);

        log.info("Discovered {} modules via SPI", discovered.size());
        return initializeModules(discovered, config);
    }

    /**
     * Initializes a specific list of modules (for programmatic registration).
     *
     * @param modules Modules to initialize
     * @param config  Configuration map
     * @return Number of successfully initialized modules
     */
    public int initializeModules(List<ModuleDescriptor> modules, Map<String, String> config) {
        // Sort by priority (lower first)
        List<ModuleDescriptor> sorted = modules.stream()
                .sorted(Comparator.comparingInt(ModuleDescriptor::priority))
                .toList();

        int successCount = 0;
        for (ModuleDescriptor module : sorted) {
            try {
                if (!validateConfig(module, config)) {
                    log.warn("Skipping module '{}': missing required configuration", module.name());
                    continue;
                }

                log.info("Initializing module: {} v{} (priority={})",
                        module.name(), module.version(), module.priority());

                // Merge optional defaults with provided config
                Map<String, String> effectiveConfig = new HashMap<>(module.optionalConfigDefaults());
                effectiveConfig.putAll(config);

                module.initialize(registry, effectiveConfig);
                loadedModules.add(module);
                successCount++;

                log.info("Module '{}' initialized successfully. Provides: {}",
                        module.name(),
                        module.providedServices().stream()
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(", ")));

            } catch (Exception e) {
                log.error("Failed to initialize module '{}': {}", module.name(), e.getMessage(), e);
            }
        }

        log.info("Module initialization complete: {}/{} modules loaded", successCount, sorted.size());
        return successCount;
    }

    /**
     * Validates that all required config keys are present.
     */
    private boolean validateConfig(ModuleDescriptor module, Map<String, String> config) {
        List<String> missing = module.requiredConfigKeys().stream()
                .filter(key -> !config.containsKey(key) || config.get(key) == null || config.get(key).isBlank())
                .toList();

        if (!missing.isEmpty()) {
            log.warn("Module '{}' missing required config keys: {}", module.name(), missing);
            return false;
        }
        return true;
    }

    /**
     * Shuts down all loaded modules in reverse order.
     */
    public void shutdownAll() {
        List<ModuleDescriptor> reversed = new ArrayList<>(loadedModules);
        Collections.reverse(reversed);

        for (ModuleDescriptor module : reversed) {
            try {
                log.info("Shutting down module: {}", module.name());
                module.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down module '{}': {}", module.name(), e.getMessage());
            }
        }
        loadedModules.clear();
    }

    /**
     * Returns the list of successfully loaded modules.
     */
    public List<ModuleDescriptor> getLoadedModules() {
        return Collections.unmodifiableList(loadedModules);
    }

    /**
     * Returns the underlying registry.
     */
    public ServiceProviderRegistry getRegistry() {
        return registry;
    }
}
