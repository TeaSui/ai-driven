package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry for workflow plugins.
 * Manages plugin lifecycle and provides tenant-filtered plugin access.
 *
 * <p>Plugins are registered globally (available to all tenants) but
 * activated per-tenant based on {@link WorkflowPlugin#isEnabled(TenantConfig)}.</p>
 */
@Slf4j
public class PluginRegistry {

    /** All registered plugins, keyed by pluginId */
    private final Map<String, WorkflowPlugin> plugins = new ConcurrentHashMap<>();

    /** Initialized plugins per tenant: tenantId → set of pluginIds */
    private final Map<String, Set<String>> initializedPlugins = new ConcurrentHashMap<>();

    /**
     * Registers a plugin globally.
     *
     * @param plugin The plugin to register
     * @throws IllegalArgumentException if a plugin with the same ID is already registered
     */
    public void register(WorkflowPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        if (plugins.containsKey(plugin.pluginId())) {
            throw new IllegalArgumentException(
                    "Plugin already registered: " + plugin.pluginId());
        }
        plugins.put(plugin.pluginId(), plugin);
        log.info("Registered plugin: {} ({})", plugin.pluginId(), plugin.displayName());
    }

    /**
     * Registers a plugin, replacing any existing plugin with the same ID.
     */
    public void registerOrReplace(WorkflowPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        plugins.put(plugin.pluginId(), plugin);
        log.info("Registered/replaced plugin: {} ({})", plugin.pluginId(), plugin.displayName());
    }

    /**
     * Returns all plugins enabled for a specific tenant.
     *
     * @param tenantConfig The tenant configuration
     * @return List of enabled plugins for this tenant
     */
    public List<WorkflowPlugin> getEnabledPlugins(TenantConfig tenantConfig) {
        return plugins.values().stream()
                .filter(p -> p.isEnabled(tenantConfig))
                .collect(Collectors.toList());
    }

    /**
     * Initializes all enabled plugins for a tenant.
     * Idempotent — already-initialized plugins are skipped.
     *
     * @param tenantConfig The tenant configuration
     * @return List of successfully initialized plugins
     */
    public List<WorkflowPlugin> initializeForTenant(TenantConfig tenantConfig) {
        String tenantId = tenantConfig.getTenantId();
        Set<String> alreadyInit = initializedPlugins.computeIfAbsent(
                tenantId, k -> ConcurrentHashMap.newKeySet());

        List<WorkflowPlugin> initialized = new ArrayList<>();

        for (WorkflowPlugin plugin : getEnabledPlugins(tenantConfig)) {
            if (alreadyInit.contains(plugin.pluginId())) {
                log.debug("Plugin {} already initialized for tenant {}", plugin.pluginId(), tenantId);
                initialized.add(plugin);
                continue;
            }

            try {
                plugin.initialize(tenantConfig);
                alreadyInit.add(plugin.pluginId());
                initialized.add(plugin);
                log.info("Initialized plugin {} for tenant {}", plugin.pluginId(), tenantId);
            } catch (WorkflowPlugin.PluginInitializationException e) {
                log.error("Failed to initialize plugin {} for tenant {}: {}",
                        plugin.pluginId(), tenantId, e.getMessage(), e);
                // Continue with other plugins — one failure shouldn't block all
            }
        }

        return initialized;
    }

    /**
     * Returns a plugin by ID.
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
     * Returns the number of registered plugins.
     */
    public int size() {
        return plugins.size();
    }

    /**
     * Shuts down all plugins for a tenant.
     */
    public void shutdownForTenant(String tenantId) {
        Set<String> initPlugins = initializedPlugins.remove(tenantId);
        if (initPlugins != null) {
            initPlugins.forEach(pluginId -> {
                WorkflowPlugin plugin = plugins.get(pluginId);
                if (plugin != null) {
                    try {
                        plugin.shutdown();
                    } catch (Exception e) {
                        log.warn("Error shutting down plugin {} for tenant {}: {}",
                                pluginId, tenantId, e.getMessage());
                    }
                }
            });
        }
    }
}
