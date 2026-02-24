package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry for workflow automation plugins.
 *
 * <p>Plugins are registered globally and activated per-tenant based on
 * {@link TenantConfig#getEnabledPlugins()}. This enables a modular monolith
 * where all plugin code is present but only activated for tenants that need it.</p>
 *
 * <p>Thread-safe via ConcurrentHashMap.</p>
 */
@Slf4j
public class PluginRegistry {

    private final Map<String, WorkflowPlugin> plugins = new ConcurrentHashMap<>();

    /**
     * Registers a plugin globally.
     *
     * @param plugin The plugin to register
     * @throws IllegalArgumentException if pluginId is null or blank
     */
    public void register(WorkflowPlugin plugin) {
        if (plugin == null || plugin.pluginId() == null || plugin.pluginId().isBlank()) {
            throw new IllegalArgumentException("Plugin must have a non-blank pluginId");
        }
        plugins.put(plugin.pluginId(), plugin);
        log.info("Registered plugin: {} v{} — {}", plugin.pluginId(), plugin.version(), plugin.description());
    }

    /**
     * Returns all plugins enabled for a specific tenant.
     *
     * @param tenantConfig The tenant configuration
     * @return List of enabled plugins for this tenant
     */
    public List<WorkflowPlugin> getEnabledPlugins(TenantConfig tenantConfig) {
        if (tenantConfig == null || tenantConfig.getEnabledPlugins() == null) {
            return List.of();
        }
        return tenantConfig.getEnabledPlugins().stream()
                .map(plugins::get)
                .filter(Objects::nonNull)
                .filter(p -> p.isEnabled(tenantConfig))
                .collect(Collectors.toList());
    }

    /**
     * Returns a specific plugin by ID.
     *
     * @param pluginId The plugin identifier
     * @return Optional containing the plugin, or empty if not found
     */
    public Optional<WorkflowPlugin> getPlugin(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId));
    }

    /**
     * Returns all registered plugin IDs.
     */
    public Set<String> getRegisteredPluginIds() {
        return Collections.unmodifiableSet(plugins.keySet());
    }

    /**
     * Returns all registered plugins.
     */
    public Collection<WorkflowPlugin> getAllPlugins() {
        return Collections.unmodifiableCollection(plugins.values());
    }

    /**
     * Checks if a plugin is registered.
     */
    public boolean isRegistered(String pluginId) {
        return plugins.containsKey(pluginId);
    }

    /**
     * Initializes all enabled plugins for a tenant.
     *
     * @param tenantConfig The tenant configuration
     */
    public void initializeForTenant(TenantConfig tenantConfig) {
        List<WorkflowPlugin> enabled = getEnabledPlugins(tenantConfig);
        log.info("Initializing {} plugins for tenant: {}", enabled.size(), tenantConfig.getTenantId());
        for (WorkflowPlugin plugin : enabled) {
            try {
                plugin.initialize(tenantConfig);
                log.info("Initialized plugin '{}' for tenant '{}'",
                        plugin.pluginId(), tenantConfig.getTenantId());
            } catch (Exception e) {
                log.error("Failed to initialize plugin '{}' for tenant '{}': {}",
                        plugin.pluginId(), tenantConfig.getTenantId(), e.getMessage(), e);
                // Non-fatal: continue with other plugins
            }
        }
    }

    /**
     * Shuts down all registered plugins.
     */
    public void shutdownAll() {
        plugins.values().forEach(plugin -> {
            try {
                plugin.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down plugin '{}': {}", plugin.pluginId(), e.getMessage());
            }
        });
    }

    /**
     * Returns the number of registered plugins.
     */
    public int size() {
        return plugins.size();
    }
}
