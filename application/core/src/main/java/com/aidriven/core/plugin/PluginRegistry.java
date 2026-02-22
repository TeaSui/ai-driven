package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for {@link WorkflowPlugin} instances.
 *
 * <p>Plugins can be registered globally (apply to all tenants) or
 * per-tenant. When executing hooks, both global and tenant-specific
 * plugins are invoked in registration order.</p>
 */
@Slf4j
public class PluginRegistry {

    private static final PluginRegistry INSTANCE = new PluginRegistry();

    /** Global plugins (tenantId == null). */
    private final List<WorkflowPlugin> globalPlugins = Collections.synchronizedList(new ArrayList<>());

    /** Per-tenant plugins keyed by tenantId. */
    private final Map<String, List<WorkflowPlugin>> tenantPlugins = new ConcurrentHashMap<>();

    private PluginRegistry() {}

    public static PluginRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a plugin. If {@link WorkflowPlugin#tenantId()} returns null,
     * the plugin is registered globally.
     *
     * @param plugin The plugin to register
     */
    public void register(WorkflowPlugin plugin) {
        log.debug("Registering plugin: {}", plugin.pluginId());
        if (plugin.tenantId() == null) {
            globalPlugins.add(plugin);
            log.info("Registered global plugin: {}", plugin.pluginId());
        } else {
            tenantPlugins.computeIfAbsent(plugin.tenantId(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(plugin);
            log.info("Registered plugin '{}' for tenant '{}'", plugin.pluginId(), plugin.tenantId());
        }
    }

    /**
     * Returns all plugins applicable to a given tenant (global + tenant-specific).
     *
     * @param tenantId The tenant ID (may be null for global-only)
     * @return Ordered list of applicable plugins
     */
    public List<WorkflowPlugin> getPluginsForTenant(String tenantId) {
        List<WorkflowPlugin> result = new ArrayList<>(globalPlugins);
        if (tenantId != null) {
            List<WorkflowPlugin> specific = tenantPlugins.getOrDefault(tenantId, List.of());
            result.addAll(specific);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Executes the {@code onTicketReceived} hook for all applicable plugins.
     */
    public com.aidriven.core.model.TicketInfo executeOnTicketReceived(
            com.aidriven.core.model.TicketInfo ticket, TenantConfig tenantConfig) {
        String tenantId = tenantConfig != null ? tenantConfig.getTenantId() : null;
        for (WorkflowPlugin plugin : getPluginsForTenant(tenantId)) {
            try {
                ticket = plugin.onTicketReceived(ticket, tenantConfig);
            } catch (Exception e) {
                log.error("Plugin '{}' threw exception in onTicketReceived: {}", plugin.pluginId(), e.getMessage(), e);
            }
        }
        return ticket;
    }

    /**
     * Executes the {@code onBeforeCodeGeneration} hook for all applicable plugins.
     */
    public WorkflowContext executeOnBeforeCodeGeneration(WorkflowContext context, TenantConfig tenantConfig) {
        String tenantId = tenantConfig != null ? tenantConfig.getTenantId() : null;
        for (WorkflowPlugin plugin : getPluginsForTenant(tenantId)) {
            try {
                context = plugin.onBeforeCodeGeneration(context, tenantConfig);
            } catch (Exception e) {
                log.error("Plugin '{}' threw exception in onBeforeCodeGeneration: {}", plugin.pluginId(), e.getMessage(), e);
            }
        }
        return context;
    }

    /**
     * Executes the {@code onAfterCodeGeneration} hook for all applicable plugins.
     */
    public WorkflowContext executeOnAfterCodeGeneration(WorkflowContext context, TenantConfig tenantConfig) {
        String tenantId = tenantConfig != null ? tenantConfig.getTenantId() : null;
        for (WorkflowPlugin plugin : getPluginsForTenant(tenantId)) {
            try {
                context = plugin.onAfterCodeGeneration(context, tenantConfig);
            } catch (Exception e) {
                log.error("Plugin '{}' threw exception in onAfterCodeGeneration: {}", plugin.pluginId(), e.getMessage(), e);
            }
        }
        return context;
    }

    /**
     * Executes the {@code onPrCreated} hook for all applicable plugins.
     */
    public void executeOnPrCreated(String ticketKey, String prUrl, TenantConfig tenantConfig) {
        String tenantId = tenantConfig != null ? tenantConfig.getTenantId() : null;
        for (WorkflowPlugin plugin : getPluginsForTenant(tenantId)) {
            try {
                plugin.onPrCreated(ticketKey, prUrl, tenantConfig);
            } catch (Exception e) {
                log.error("Plugin '{}' threw exception in onPrCreated: {}", plugin.pluginId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Removes all registered plugins. Primarily for testing.
     */
    public void clear() {
        globalPlugins.clear();
        tenantPlugins.clear();
    }

    /**
     * Returns the total number of registered plugins.
     */
    public int size() {
        int tenantTotal = tenantPlugins.values().stream().mapToInt(List::size).sum();
        return globalPlugins.size() + tenantTotal;
    }
}
