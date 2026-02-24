package com.aidriven.core.plugin;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for available plugins in the workflow automation system.
 *
 * <p>Plugins are registered globally (available to all tenants) but
 * enabled per-tenant via {@link com.aidriven.core.tenant.TenantConfig#getEnabledPlugins()}.
 *
 * <p>This follows the same pattern as {@link com.aidriven.core.agent.tool.ToolRegistry}
 * but operates at the plugin descriptor level (metadata), not the tool execution level.
 *
 * <p>Thread-safe for concurrent Lambda invocations.
 */
@Slf4j
public class PluginRegistry {

    private final Map<String, PluginDescriptor> plugins = new ConcurrentHashMap<>();

    /**
     * Registers a plugin descriptor.
     *
     * @param descriptor The plugin to register
     * @throws IllegalArgumentException if the descriptor is invalid
     */
    public void register(PluginDescriptor descriptor) {
        descriptor.validate();
        plugins.put(descriptor.namespace(), descriptor);
        log.info("Registered plugin: {} v{} — {}",
                descriptor.namespace(), descriptor.version(), descriptor.displayName());
    }

    /**
     * Finds a plugin by namespace.
     *
     * @param namespace The plugin namespace
     * @return Optional containing the descriptor, or empty if not found
     */
    public Optional<PluginDescriptor> find(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(plugins.get(namespace));
    }

    /**
     * Returns all registered plugin namespaces.
     */
    public Set<String> getNamespaces() {
        return Collections.unmodifiableSet(plugins.keySet());
    }

    /**
     * Returns all registered plugin descriptors.
     */
    public Collection<PluginDescriptor> getAllPlugins() {
        return Collections.unmodifiableCollection(plugins.values());
    }

    /**
     * Returns plugins enabled for a given set of namespaces.
     *
     * @param enabledNamespaces The set of enabled namespaces
     * @return List of enabled plugin descriptors
     */
    public List<PluginDescriptor> getEnabledPlugins(Set<String> enabledNamespaces) {
        if (enabledNamespaces == null || enabledNamespaces.isEmpty()) {
            return List.of();
        }
        return plugins.values().stream()
                .filter(p -> enabledNamespaces.contains(p.namespace()))
                .toList();
    }

    /**
     * Checks if a plugin is registered.
     *
     * @param namespace The plugin namespace
     * @return true if registered
     */
    public boolean isRegistered(String namespace) {
        return namespace != null && plugins.containsKey(namespace);
    }

    /**
     * Returns the number of registered plugins.
     */
    public int size() {
        return plugins.size();
    }
}
