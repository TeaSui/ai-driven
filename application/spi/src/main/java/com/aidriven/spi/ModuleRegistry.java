package com.aidriven.spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry for discovering, validating, and managing pluggable modules.
 *
 * <p>The registry supports two discovery modes:</p>
 * <ul>
 *   <li><b>ServiceLoader</b>: Automatic discovery via {@code META-INF/services}</li>
 *   <li><b>Programmatic</b>: Manual registration via {@link #register(ModuleProvider)}</li>
 * </ul>
 *
 * <p>Tenant activation flow:</p>
 * <ol>
 *   <li>Discover all available modules</li>
 *   <li>Filter by tenant's enabled modules</li>
 *   <li>Validate dependency graph</li>
 *   <li>Initialize in dependency order</li>
 * </ol>
 */
public class ModuleRegistry {

    private final Map<String, ModuleProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, ModuleProvider> initialized = new ConcurrentHashMap<>();

    /**
     * Discovers and registers all modules available via ServiceLoader.
     *
     * @return Number of modules discovered
     */
    public int discoverModules() {
        ServiceLoader<ModuleProvider> loader = ServiceLoader.load(ModuleProvider.class);
        int count = 0;
        for (ModuleProvider provider : loader) {
            register(provider);
            count++;
        }
        return count;
    }

    /**
     * Manually registers a module provider.
     *
     * @param provider The module provider to register
     * @throws IllegalArgumentException if a module with the same ID is already registered
     */
    public void register(ModuleProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        ModuleDescriptor descriptor = provider.descriptor();
        Objects.requireNonNull(descriptor, "descriptor must not be null");

        ModuleProvider existing = providers.putIfAbsent(descriptor.id(), provider);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Module '" + descriptor.id() + "' is already registered");
        }
    }

    /**
     * Returns all registered module descriptors.
     */
    public List<ModuleDescriptor> listModules() {
        return providers.values().stream()
                .map(ModuleProvider::descriptor)
                .sorted(Comparator.comparing(ModuleDescriptor::id))
                .toList();
    }

    /**
     * Returns modules filtered by category.
     */
    public List<ModuleDescriptor> listModules(ModuleCategory category) {
        return providers.values().stream()
                .map(ModuleProvider::descriptor)
                .filter(d -> d.category() == category)
                .sorted(Comparator.comparing(ModuleDescriptor::id))
                .toList();
    }

    /**
     * Gets a specific module provider by ID.
     */
    public Optional<ModuleProvider> getProvider(String moduleId) {
        return Optional.ofNullable(providers.get(moduleId));
    }

    /**
     * Initializes modules for a tenant based on their configuration.
     * Only initializes modules that are enabled for the tenant.
     *
     * @param context Tenant context with enabled modules and configuration
     * @return List of successfully initialized module IDs
     * @throws ModuleInitializationException if a required module fails to initialize
     */
    public List<String> initializeForTenant(TenantContext context) throws ModuleInitializationException {
        Set<String> enabledModules = context.getEnabledModules();
        List<String> initOrder = resolveInitializationOrder(enabledModules);
        List<String> successfullyInitialized = new ArrayList<>();

        for (String moduleId : initOrder) {
            ModuleProvider provider = providers.get(moduleId);
            if (provider == null) {
                throw new ModuleInitializationException(moduleId,
                        "Module not found in registry. Available: " + providers.keySet());
            }

            try {
                provider.initialize(context);
                initialized.put(moduleId, provider);
                successfullyInitialized.add(moduleId);
            } catch (ModuleInitializationException e) {
                throw e;
            } catch (Exception e) {
                throw new ModuleInitializationException(moduleId, e.getMessage(), e);
            }
        }

        return successfullyInitialized;
    }

    /**
     * Validates that all dependencies for the enabled modules are satisfied.
     *
     * @param enabledModules Set of module IDs to validate
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateDependencies(Set<String> enabledModules) {
        List<String> errors = new ArrayList<>();

        for (String moduleId : enabledModules) {
            ModuleProvider provider = providers.get(moduleId);
            if (provider == null) {
                errors.add("Module '" + moduleId + "' is not registered");
                continue;
            }

            for (String dep : provider.descriptor().dependencies()) {
                if (!enabledModules.contains(dep)) {
                    errors.add(String.format("Module '%s' requires '%s' which is not enabled",
                            moduleId, dep));
                }
            }
        }

        return errors;
    }

    /**
     * Validates that required configuration keys are present for enabled modules.
     *
     * @param context Tenant context to validate against
     * @return List of missing configuration keys
     */
    public List<String> validateConfiguration(TenantContext context) {
        List<String> missing = new ArrayList<>();

        for (String moduleId : context.getEnabledModules()) {
            ModuleProvider provider = providers.get(moduleId);
            if (provider == null) continue;

            for (String configKey : provider.descriptor().requiredConfigs()) {
                if (context.getConfig(configKey).isEmpty()) {
                    missing.add(String.format("Module '%s' requires config '%s'", moduleId, configKey));
                }
            }
        }

        return missing;
    }

    /**
     * Returns health status for all initialized modules.
     */
    public Map<String, HealthStatus> healthCheck() {
        Map<String, HealthStatus> statuses = new LinkedHashMap<>();
        for (Map.Entry<String, ModuleProvider> entry : initialized.entrySet()) {
            try {
                statuses.put(entry.getKey(), entry.getValue().healthCheck());
            } catch (Exception e) {
                statuses.put(entry.getKey(), HealthStatus.unhealthy("Health check failed: " + e.getMessage()));
            }
        }
        return statuses;
    }

    /**
     * Shuts down all initialized modules in reverse order.
     */
    public void shutdownAll() {
        List<String> reverseOrder = new ArrayList<>(initialized.keySet());
        Collections.reverse(reverseOrder);

        for (String moduleId : reverseOrder) {
            try {
                initialized.get(moduleId).shutdown();
            } catch (Exception e) {
                // Log but continue shutdown
            }
        }
        initialized.clear();
    }

    /**
     * Returns the number of registered modules.
     */
    public int size() {
        return providers.size();
    }

    /**
     * Returns the set of registered module IDs.
     */
    public Set<String> getRegisteredIds() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    /**
     * Resolves initialization order based on dependencies (topological sort).
     */
    List<String> resolveInitializationOrder(Set<String> enabledModules) {
        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (String moduleId : enabledModules) {
            graph.putIfAbsent(moduleId, new HashSet<>());
            inDegree.putIfAbsent(moduleId, 0);

            ModuleProvider provider = providers.get(moduleId);
            if (provider != null) {
                for (String dep : provider.descriptor().dependencies()) {
                    if (enabledModules.contains(dep)) {
                        graph.computeIfAbsent(dep, k -> new HashSet<>()).add(moduleId);
                        inDegree.merge(moduleId, 1, Integer::sum);
                        inDegree.putIfAbsent(dep, 0);
                    }
                }
            }
        }

        // Kahn's algorithm
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            order.add(current);

            for (String dependent : graph.getOrDefault(current, Set.of())) {
                int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(dependent);
                }
            }
        }

        // If not all modules are in the order, there's a cycle
        if (order.size() < enabledModules.size()) {
            Set<String> missing = new HashSet<>(enabledModules);
            missing.removeAll(order);
            throw new IllegalStateException("Circular dependency detected involving modules: " + missing);
        }

        return order;
    }
}
