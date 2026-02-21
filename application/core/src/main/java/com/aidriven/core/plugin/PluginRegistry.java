package com.aidriven.core.plugin;

import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Discovers, initializes, and manages plugin modules.
 *
 * <p>Uses Java SPI ({@link ServiceLoader}) to discover available plugins,
 * then initializes only those enabled for the current tenant.</p>
 *
 * <p>Thread-safe: plugin instances are cached per tenant ID.</p>
 */
@Slf4j
public class PluginRegistry {

    private final List<PluginModule> availablePlugins;
    private final Map<String, List<PluginModule>> initializedPlugins = new ConcurrentHashMap<>();
    private final SecretsService secretsService;

    /**
     * Creates a registry that discovers plugins via SPI.
     *
     * @param secretsService Service for resolving secrets during plugin initialization
     */
    public PluginRegistry(SecretsService secretsService) {
        this.secretsService = secretsService;
        this.availablePlugins = discoverPlugins();
        log.info("Discovered {} plugin modules: {}", availablePlugins.size(),
                availablePlugins.stream().map(p -> p.descriptor().id()).collect(Collectors.joining(", ")));
    }

    /**
     * Creates a registry with explicitly provided plugins (for testing).
     */
    public PluginRegistry(SecretsService secretsService, List<PluginModule> plugins) {
        this.secretsService = secretsService;
        this.availablePlugins = new ArrayList<>(plugins);
    }

    /**
     * Gets all tool providers for a tenant, initializing plugins as needed.
     *
     * @param tenantContext Tenant configuration
     * @return List of tool providers from all enabled plugins
     */
    public List<ToolProvider> getToolProviders(TenantContext tenantContext) {
        List<PluginModule> plugins = initializedPlugins.computeIfAbsent(
                tenantContext.tenantId(),
                id -> initializePlugins(tenantContext));

        return plugins.stream()
                .flatMap(p -> p.getToolProviders().stream())
                .toList();
    }

    /**
     * Returns descriptors for all discovered plugins.
     */
    public List<PluginDescriptor> getAvailablePlugins() {
        return availablePlugins.stream()
                .map(PluginModule::descriptor)
                .toList();
    }

    /**
     * Returns descriptors for plugins enabled for a tenant.
     */
    public List<PluginDescriptor> getEnabledPlugins(TenantContext tenantContext) {
        return availablePlugins.stream()
                .filter(p -> isPluginEnabled(p, tenantContext))
                .map(PluginModule::descriptor)
                .toList();
    }

    /**
     * Shuts down all initialized plugins for a tenant.
     */
    public void shutdownTenant(String tenantId) {
        List<PluginModule> plugins = initializedPlugins.remove(tenantId);
        if (plugins != null) {
            plugins.forEach(p -> {
                try {
                    p.shutdown();
                } catch (Exception e) {
                    log.warn("Error shutting down plugin '{}' for tenant {}: {}",
                            p.descriptor().id(), tenantId, e.getMessage());
                }
            });
            log.info("Shut down {} plugins for tenant {}", plugins.size(), tenantId);
        }
    }

    /**
     * Shuts down all plugins for all tenants.
     */
    public void shutdownAll() {
        initializedPlugins.keySet().forEach(this::shutdownTenant);
    }

    private List<PluginModule> initializePlugins(TenantContext tenantContext) {
        List<PluginModule> enabled = new ArrayList<>();

        for (PluginModule plugin : availablePlugins) {
            if (!isPluginEnabled(plugin, tenantContext)) {
                log.debug("Plugin '{}' not enabled for tenant {}",
                        plugin.descriptor().id(), tenantContext.tenantId());
                continue;
            }

            try {
                plugin.initialize(tenantContext, secretsService);
                enabled.add(plugin);
                log.info("Initialized plugin '{}' for tenant {} ({} tool providers)",
                        plugin.descriptor().id(), tenantContext.tenantId(),
                        plugin.getToolProviders().size());
            } catch (Exception e) {
                log.error("Failed to initialize plugin '{}' for tenant {}: {}",
                        plugin.descriptor().id(), tenantContext.tenantId(), e.getMessage(), e);
                // Continue with remaining plugins — one failure shouldn't block all
            }
        }

        return enabled;
    }

    private boolean isPluginEnabled(PluginModule plugin, TenantContext tenantContext) {
        String pluginId = plugin.descriptor().id();
        return tenantContext.isModuleEnabled(pluginId);
    }

    private List<PluginModule> discoverPlugins() {
        List<PluginModule> plugins = new ArrayList<>();
        ServiceLoader<PluginModule> loader = ServiceLoader.load(PluginModule.class);
        for (PluginModule plugin : loader) {
            plugins.add(plugin);
        }
        return plugins;
    }
}