package com.aidriven.spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry that discovers, manages, and provides access to {@link ServiceModule} instances.
 *
 * <p>Modules are discovered via Java's {@link ServiceLoader} mechanism
 * (META-INF/services) or registered programmatically. The registry
 * handles dependency ordering and lifecycle management.</p>
 *
 * <p>Thread-safe: modules can be queried concurrently after initialization.</p>
 *
 * @see ServiceModule
 * @see TenantContext
 */
public class ModuleRegistry {

    private final Map<String, ServiceModule> modules = new ConcurrentHashMap<>();
    private final Map<String, Boolean> initialized = new ConcurrentHashMap<>();

    /**
     * Discovers and registers all modules available on the classpath
     * via {@link ServiceLoader}.
     *
     * @return Number of modules discovered
     */
    public int discoverModules() {
        ServiceLoader<ServiceModule> loader = ServiceLoader.load(ServiceModule.class);
        int count = 0;
        for (ServiceModule module : loader) {
            register(module);
            count++;
        }
        return count;
    }

    /**
     * Programmatically registers a module.
     *
     * @param module The module to register
     * @throws IllegalArgumentException if a module with the same ID is already registered
     */
    public void register(ServiceModule module) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(module.moduleId(), "moduleId must not be null");

        ServiceModule existing = modules.putIfAbsent(module.moduleId(), module);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Module '" + module.moduleId() + "' is already registered");
        }
    }

    /**
     * Initializes a specific module with the given configuration.
     * Ensures dependencies are initialized first (topological order).
     *
     * @param moduleId Module to initialize
     * @param config   Module configuration
     * @throws ModuleInitializationException if initialization fails
     */
    public void initializeModule(String moduleId, Map<String, String> config)
            throws ModuleInitializationException {
        ServiceModule module = modules.get(moduleId);
        if (module == null) {
            throw new ModuleInitializationException(moduleId, "Module not found in registry");
        }

        // Check dependencies
        for (String depId : module.dependencies()) {
            if (!isInitialized(depId)) {
                throw new ModuleInitializationException(moduleId,
                        "Dependency '" + depId + "' is not initialized");
            }
        }

        module.initialize(config);
        initialized.put(moduleId, true);
    }

    /**
     * Initializes all registered modules in dependency order.
     *
     * @param configs Map of moduleId → configuration
     * @throws ModuleInitializationException if any module fails to initialize
     */
    public void initializeAll(Map<String, Map<String, String>> configs)
            throws ModuleInitializationException {
        List<String> order = topologicalSort();
        for (String moduleId : order) {
            Map<String, String> config = configs.getOrDefault(moduleId, Map.of());
            initializeModule(moduleId, config);
        }
    }

    /**
     * Returns a module by ID, cast to the expected type.
     *
     * @param moduleId The module ID
     * @param type     Expected module type
     * @return The module instance
     * @throws NoSuchElementException if the module is not registered
     * @throws ClassCastException     if the module is not of the expected type
     */
    @SuppressWarnings("unchecked")
    public <T extends ServiceModule> T getModule(String moduleId, Class<T> type) {
        ServiceModule module = modules.get(moduleId);
        if (module == null) {
            throw new NoSuchElementException("Module not found: " + moduleId);
        }
        return type.cast(module);
    }

    /**
     * Returns a module by ID.
     */
    public Optional<ServiceModule> getModule(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    /**
     * Returns all registered module IDs.
     */
    public Set<String> getRegisteredModuleIds() {
        return Collections.unmodifiableSet(modules.keySet());
    }

    /**
     * Returns all initialized module IDs.
     */
    public Set<String> getInitializedModuleIds() {
        return initialized.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Checks if a module is registered.
     */
    public boolean isRegistered(String moduleId) {
        return modules.containsKey(moduleId);
    }

    /**
     * Checks if a module is initialized.
     */
    public boolean isInitialized(String moduleId) {
        return Boolean.TRUE.equals(initialized.get(moduleId));
    }

    /**
     * Shuts down all initialized modules in reverse dependency order.
     */
    public void shutdownAll() {
        List<String> order = topologicalSort();
        Collections.reverse(order);
        for (String moduleId : order) {
            if (isInitialized(moduleId)) {
                try {
                    modules.get(moduleId).shutdown();
                    initialized.put(moduleId, false);
                } catch (Exception e) {
                    // Log but continue shutting down other modules
                }
            }
        }
    }

    /**
     * Returns health status of all initialized modules.
     */
    public Map<String, Boolean> healthCheck() {
        Map<String, Boolean> health = new LinkedHashMap<>();
        for (String moduleId : getInitializedModuleIds()) {
            try {
                health.put(moduleId, modules.get(moduleId).isHealthy());
            } catch (Exception e) {
                health.put(moduleId, false);
            }
        }
        return health;
    }

    /**
     * Topological sort of modules based on dependencies.
     * Modules with no dependencies come first.
     */
    List<String> topologicalSort() {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();

        for (String id : modules.keySet()) {
            inDegree.putIfAbsent(id, 0);
            adjacency.putIfAbsent(id, new ArrayList<>());
        }

        for (Map.Entry<String, ServiceModule> entry : modules.entrySet()) {
            for (String dep : entry.getValue().dependencies()) {
                if (modules.containsKey(dep)) {
                    adjacency.computeIfAbsent(dep, k -> new ArrayList<>()).add(entry.getKey());
                    inDegree.merge(entry.getKey(), 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            for (String neighbor : adjacency.getOrDefault(current, List.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        // Add any remaining modules (circular deps — shouldn't happen but be safe)
        for (String id : modules.keySet()) {
            if (!sorted.contains(id)) {
                sorted.add(id);
            }
        }

        return sorted;
    }
}
