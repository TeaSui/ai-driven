package com.aidriven.core.plugin;

import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.tenant.TenantConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for workflow automation plugins.
 *
 * <p>Manages plugin lifecycle and provides tenant-aware tool registration.
 * Plugins are registered globally but activated per-tenant based on
 * tenant configuration.
 *
 * <p>Usage:
 * <pre>{@code
 * PluginRegistry pluginRegistry = new PluginRegistry();
 * pluginRegistry.register(new MonitoringPlugin());
 * pluginRegistry.register(new MessagingPlugin());
 *
 * // For a specific tenant request:
 * ToolRegistry toolRegistry = pluginRegistry.buildToolRegistry(tenantConfig);
 * }</pre>
 */
@Slf4j
public class PluginRegistry {

    private final Map<String, WorkflowPlugin> plugins = new ConcurrentHashMap<>();

    /**
     * Registers a plugin in the registry.
     *
     * @param plugin The plugin to register
     * @throws IllegalArgumentException if plugin namespace is null or blank
     */
    public void register(WorkflowPlugin plugin) {
        if (plugin == null || plugin.namespace() == null || plugin.namespace().isBlank()) {
            throw new IllegalArgumentException("Plugin must have a non-blank namespace");
        }
        plugins.put(plugin.namespace(), plugin);
        plugin.onRegister();
        log.info("Registered plugin: {} ({})", plugin.namespace(), plugin.displayName());
    }

    /**
     * Deregisters a plugin from the registry.
     *
     * @param namespace The plugin namespace to remove
     * @return true if the plugin was removed
     */
    public boolean deregister(String namespace) {
        WorkflowPlugin removed = plugins.remove(namespace);
        if (removed != null) {
            removed.onDeregister();
            log.info("Deregistered plugin: {}", namespace);
            return true;
        }
        return false;
    }

    /**
     * Retrieves a plugin by namespace.
     *
     * @param namespace The plugin namespace
     * @return Optional containing the plugin, or empty if not found
     */
    public Optional<WorkflowPlugin> getPlugin(String namespace) {
        return Optional.ofNullable(plugins.get(namespace));
    }

    /**
     * Returns all registered plugins.
     */
    public Collection<WorkflowPlugin> getAllPlugins() {
        return Collections.unmodifiableCollection(plugins.values());
    }

    /**
     * Returns the set of registered plugin namespaces.
     */
    public Set<String> getNamespaces() {
        return Collections.unmodifiableSet(plugins.keySet());
    }

    /**
     * Builds a ToolRegistry populated with tool providers for the given tenant.
     *
     * <p>Only plugins that are:
     * <ol>
     *   <li>Enabled in the tenant's configuration</li>
     *   <li>Compatible with the tenant (via {@link WorkflowPlugin#supportsenant})</li>
     * </ol>
     * will have their tool providers registered.
     *
     * @param tenantConfig The tenant configuration
     * @param coreProviders Core tool providers always included (source control, issue tracker, etc.)
     * @return A configured ToolRegistry for this tenant
     */
    public ToolRegistry buildToolRegistry(TenantConfig tenantConfig, List<ToolProvider> coreProviders) {
        ToolRegistry toolRegistry = new ToolRegistry();

        // Register core providers (always enabled)
        for (ToolProvider provider : coreProviders) {
            toolRegistry.register(provider);
        }

        // Register tenant-enabled plugins
        for (WorkflowPlugin plugin : plugins.values()) {
            String ns = plugin.namespace();

            if (!plugin.supportsenant(tenantConfig)) {
                log.debug("Plugin '{}' does not support tenant '{}', skipping",
                        ns, tenantConfig.getTenantId());
                continue;
            }

            if (!tenantConfig.isPluginEnabled(ns)) {
                log.debug("Plugin '{}' not enabled for tenant '{}', skipping",
                        ns, tenantConfig.getTenantId());
                continue;
            }

            try {
                List<ToolProvider> providers = plugin.createToolProviders(tenantConfig);
                for (ToolProvider provider : providers) {
                    toolRegistry.register(provider);
                }
                log.info("Registered {} tool providers from plugin '{}' for tenant '{}'",
                        providers.size(), ns, tenantConfig.getTenantId());
            } catch (Exception e) {
                log.error("Failed to create tool providers for plugin '{}' (tenant '{}'): {}",
                        ns, tenantConfig.getTenantId(), e.getMessage(), e);
                // Continue with other plugins — one failure shouldn't block all
            }
        }

        return toolRegistry;
    }

    /**
     * Returns the number of registered plugins.
     */
    public int size() {
        return plugins.size();
    }
}
