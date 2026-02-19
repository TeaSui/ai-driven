package com.aidriven.spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for managing service module lifecycle.
 *
 * <p>Supports both explicit registration and ServiceLoader-based discovery.
 * Each tenant gets its own set of initialized modules.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * ModuleRegistry registry = new ModuleRegistry();
 * registry.register(new JiraModule());
 * registry.register(new GitHubModule());
 * registry.register(new ClaudeModule());
 *
 * // Initialize for a specific tenant
 * registry.initializeAll(tenantContext);
 *
 * // Get a specific module
 * IssueTrackerModule jira = registry.getModule("jira", IssueTrackerModule.class);
 * }</pre>
 */
public class ModuleRegistry {

    private final Map<String, ServiceModule> modules = new ConcurrentHashMap<>();
    private final Map<String, Boolean> initialized = new ConcurrentHashMap<>();

    /**
     * Registers a module. Replaces any existing module with the same ID.
     */
    public void register(ServiceModule module) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(module.id(), "module id must not be null");
        modules.put(module.id(), module);
        initialized.put(module.id(), false);
    }

    /**
     * Discovers and registers modules via {@link ServiceLoader}.
     */
    public void discoverModules() {
        ServiceLoader<ServiceModule> loader = ServiceLoader.load(ServiceModule.class);
        for (ServiceModule module : loader) {
            register(module);
        }
    }

    /**
     * Initializes all registered modules with the given context.
     *
     * @param context Tenant-specific module context
     * @return List of module IDs that failed to initialize
     */
    public List<String> initializeAll(ModuleContext context) {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, ServiceModule> entry : modules.entrySet()) {
            try {
                entry.getValue().initialize(context);
                initialized.put(entry.getKey(), true);
            } catch (ModuleInitializationException e) {
                failures.add(entry.getKey());
            }
        }
        return failures;
    }

    /**
     * Initializes a single module by ID.
     */
    public void initialize(String moduleId, ModuleContext context) throws ModuleInitializationException {
        ServiceModule module = modules.get(moduleId);
        if (module == null) {
            throw new ModuleInitializationException(moduleId, "Module not registered");
        }
        module.initialize(context);
        initialized.put(moduleId, true);
    }

    /**
     * Returns a module by ID, cast to the expected type.
     *
     * @throws IllegalStateException if the module is not registered or not initialized
     */
    @SuppressWarnings("unchecked")
    public <T extends ServiceModule> T getModule(String id, Class<T> type) {
        ServiceModule module = modules.get(id);
        if (module == null) {
            throw new IllegalStateException("Module not registered: " + id);
        }
        if (!Boolean.TRUE.equals(initialized.get(id))) {
            throw new IllegalStateException("Module not initialized: " + id);
        }
        if (!type.isInstance(module)) {
            throw new IllegalStateException(
                    String.format("Module '%s' is %s, expected %s",
                            id, module.getClass().getSimpleName(), type.getSimpleName()));
        }
        return (T) module;
    }

    /**
     * Returns an optional module by ID.
     */
    @SuppressWarnings("unchecked")
    public <T extends ServiceModule> Optional<T> findModule(String id, Class<T> type) {
        try {
            return Optional.of(getModule(id, type));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns all modules of a given category.
     */
    public List<ServiceModule> getModulesByCategory(ModuleCategory category) {
        return modules.values().stream()
                .filter(m -> m.category() == category)
                .filter(m -> Boolean.TRUE.equals(initialized.get(m.id())))
                .collect(Collectors.toList());
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
     * Performs health check on all initialized modules.
     *
     * @return Map of module ID → health status
     */
    public Map<String, Boolean> healthCheck() {
        Map<String, Boolean> health = new LinkedHashMap<>();
        for (Map.Entry<String, ServiceModule> entry : modules.entrySet()) {
            if (Boolean.TRUE.equals(initialized.get(entry.getKey()))) {
                try {
                    health.put(entry.getKey(), entry.getValue().isHealthy());
                } catch (Exception e) {
                    health.put(entry.getKey(), false);
                }
            }
        }
        return health;
    }

    /**
     * Shuts down all initialized modules.
     */
    public void shutdownAll() {
        for (Map.Entry<String, ServiceModule> entry : modules.entrySet()) {
            if (Boolean.TRUE.equals(initialized.get(entry.getKey()))) {
                try {
                    entry.getValue().shutdown();
                } catch (Exception e) {
                    // Log but continue shutting down other modules
                }
                initialized.put(entry.getKey(), false);
            }
        }
    }

    /**
     * Returns the count of registered modules.
     */
    public int size() {
        return modules.size();
    }
}
