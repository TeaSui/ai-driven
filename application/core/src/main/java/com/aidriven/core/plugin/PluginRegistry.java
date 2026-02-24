package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for workflow plugins.
 * Manages plugin lifecycle and tenant-specific activation.
 *
 * <p>Plugin registration is global; activation is per-tenant.
 * A plugin is activated for a tenant if:
 * <ol>
 *   <li>The plugin is registered in this registry</li>
 *   <li>{@link WorkflowPlugin#isApplicable(TenantConfig)} returns true</li>
 *   <li>The tenant's {@code enabledPlugins} set contains the plugin ID</li>
 * </ol>
 */
@Slf4j
public class PluginRegistry {

    private final Map<String, WorkflowPlugin> plugins = new ConcurrentHashMap<>();

    /**
     * Registers a plugin globally.
     *
     * @param plugin The plugin to register
     * @throws IllegalArgumentException if a plugin with the same ID is already registered
     */
    public void register(WorkflowPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        String id = plugin.pluginId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Plugin must have a non-blank pluginId");
        }
        if (plugins.containsKey(id)) {
            log.warn("Plugin '{}' is already registered — overwriting", id);
        }
        plugins.put(id, plugin);
        log.info("Registered plugin: {} ({})", id, plugin.displayName());
    }

    /**
     * Returns all plugins that are active for the given tenant.
     * A plugin is active if it is registered, applicable, and enabled in tenant config.
     *
     * @param tenant The tenant configuration
     * @return List of active plugins for this tenant
     */
    public List<WorkflowPlugin> getActivePlugins(TenantConfig tenant) {
        if (tenant == null) {
            return List.of();
        }
        return plugins.values().stream()
                .filter(p -> tenant.isPluginEnabled(p.pluginId()))
                .filter(p -> p.isApplicable(tenant))
                .collect(Collectors.toList());
    }

    /**
     * Initializes all active plugins for a tenant.
     * Should be called once per tenant during onboarding or cold start.
     *
     * @param tenant The tenant configuration
     */
    public void initializeForTenant(TenantConfig tenant) {
        List<WorkflowPlugin> active = getActivePlugins(tenant);
        log.info("Initializing {} plugins for tenant: {}", active.size(), tenant.getTenantId());
        for (WorkflowPlugin plugin : active) {
            try {
                Map<String, String> conf = tenant.getPluginConfig(plugin.pluginId());
                plugin.initialize(tenant, conf);
                log.info("Initialized plugin '{}' for tenant '{}'",
                        plugin.pluginId(), tenant.getTenantId());
            } catch (Exception e) {
                log.error("Failed to initialize plugin '{}' for tenant '{}': {}",
                        plugin.pluginId(), tenant.getTenantId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Returns a plugin by ID, if registered.
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
     * Returns the total number of registered plugins.
     */
    public int size() {
        return plugins.size();
    }
}
