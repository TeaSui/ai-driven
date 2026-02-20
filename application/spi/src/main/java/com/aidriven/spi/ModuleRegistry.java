package com.aidriven.spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for discovering and managing pluggable modules.
 *
 * <p>Modules are discovered via {@link ServiceLoader} and registered by ID.
 * The registry supports multi-tenant scenarios where different tenants
 * may have different module configurations.</p>
 *
 * <p>Thread-safe: all operations use ConcurrentHashMap.</p>
 */
public class ModuleRegistry {

    private final Map<String, AiDrivenModule> modules = new ConcurrentHashMap<>();
    private final Map<ModuleType, List<String>> typeIndex = new ConcurrentHashMap<>();

    /**
     * Discovers and registers all modules available on the classpath
     * via {@link ServiceLoader}.
     *
     * @return Number of modules discovered
     */
    public int discoverModules() {
        ServiceLoader<AiDrivenModule> loader = ServiceLoader.load(AiDrivenModule.class);
        int count = 0;
        for (AiDrivenModule module : loader) {
            register(module);
            count++;
        }
        return count;
    }

    /**
     * Manually register a module.
     *
     * @param module The module to register
     * @throws IllegalStateException if a module with the same ID is already registered
     */
    public void register(AiDrivenModule module) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(module.id(), "module id must not be null");

        AiDrivenModule existing = modules.putIfAbsent(module.id(), module);
        if (existing != null) {
            throw new IllegalStateException(
                    "Module with id '" + module.id() + "' is already registered (" + existing.getClass().getName() + ")");
        }

        typeIndex.computeIfAbsent(module.type(), k -> new ArrayList<>()).add(module.id());
    }

    /**
     * Get a module by ID.
     *
     * @param id Module identifier
     * @return Optional containing the module, or empty if not found
     */
    public Optional<AiDrivenModule> get(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    /**
     * Get a module by ID, cast to a specific type.
     *
     * @param id   Module identifier
     * @param type Expected module class
     * @return Optional containing the typed module
     */
    @SuppressWarnings("unchecked")
    public <T extends AiDrivenModule> Optional<T> get(String id, Class<T> type) {
        return Optional.ofNullable(modules.get(id))
                .filter(type::isInstance)
                .map(m -> (T) m);
    }

    /**
     * Get a required module by ID.
     *
     * @param id Module identifier
     * @return The module
     * @throws NoSuchElementException if not found
     */
    public AiDrivenModule getRequired(String id) {
        return get(id).orElseThrow(() ->
                new NoSuchElementException("Module not found: " + id));
    }

    /**
     * Get all modules of a specific type.
     *
     * @param type Module type
     * @return List of modules (may be empty)
     */
    public List<AiDrivenModule> getByType(ModuleType type) {
        List<String> ids = typeIndex.getOrDefault(type, List.of());
        return ids.stream()
                .map(modules::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get the first (or only) module of a specific type.
     * Useful when only one module of a type is expected (e.g., one issue tracker).
     */
    public Optional<AiDrivenModule> getFirstByType(ModuleType type) {
        return getByType(type).stream().findFirst();
    }

    /**
     * Initialize all registered modules with the given context.
     *
     * @param context Module context with tenant configuration
     * @return Map of module ID → initialization result (null for success, exception for failure)
     */
    public Map<String, Exception> initializeAll(ModuleContext context) {
        Map<String, Exception> results = new LinkedHashMap<>();
        for (Map.Entry<String, AiDrivenModule> entry : modules.entrySet()) {
            try {
                entry.getValue().initialize(context);
                results.put(entry.getKey(), null);
            } catch (Exception e) {
                results.put(entry.getKey(), e);
            }
        }
        return results;
    }

    /**
     * Run health checks on all registered modules.
     *
     * @return Map of module ID → health check result
     */
    public Map<String, HealthCheckResult> healthCheckAll() {
        Map<String, HealthCheckResult> results = new LinkedHashMap<>();
        for (Map.Entry<String, AiDrivenModule> entry : modules.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().healthCheck());
            } catch (Exception e) {
                results.put(entry.getKey(), HealthCheckResult.unhealthy("Health check threw exception", e));
            }
        }
        return results;
    }

    /**
     * Shutdown all modules gracefully.
     */
    public void shutdownAll() {
        for (AiDrivenModule module : modules.values()) {
            try {
                module.shutdown();
            } catch (Exception e) {
                // Log but don't propagate — best-effort shutdown
            }
        }
        modules.clear();
        typeIndex.clear();
    }

    /**
     * Returns all registered module IDs.
     */
    public Set<String> getRegisteredIds() {
        return Collections.unmodifiableSet(modules.keySet());
    }

    /**
     * Returns the count of registered modules.
     */
    public int size() {
        return modules.size();
    }

    /**
     * Removes a module by ID.
     *
     * @param id Module identifier
     * @return true if the module was found and removed
     */
    public boolean unregister(String id) {
        AiDrivenModule removed = modules.remove(id);
        if (removed != null) {
            List<String> typeIds = typeIndex.get(removed.type());
            if (typeIds != null) {
                typeIds.remove(id);
            }
            return true;
        }
        return false;
    }
}
