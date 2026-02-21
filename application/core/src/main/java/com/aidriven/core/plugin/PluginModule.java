package com.aidriven.core.plugin;

import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.tenant.TenantContext;

import java.util.List;

/**
 * SPI interface for pluggable integration modules.
 *
 * <p>Each module (Jira, GitHub, Bitbucket, Datadog, Slack, etc.) implements
 * this interface and registers via {@code META-INF/services}.</p>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #descriptor()} — called once at discovery time</li>
 *   <li>{@link #initialize(TenantContext, SecretsService)} — called per-tenant to create clients</li>
 *   <li>{@link #getToolProviders()} — returns tool providers for the agent</li>
 *   <li>{@link #shutdown()} — cleanup when tenant context is released</li>
 * </ol>
 */
public interface PluginModule {

    /**
     * Returns the plugin descriptor with metadata.
     */
    PluginDescriptor descriptor();

    /**
     * Initializes the plugin for a specific tenant.
     * Creates API clients, establishes connections, etc.
     *
     * @param tenantContext Tenant-specific configuration and credentials
     * @param secretsService Service for resolving secret ARNs to values
     */
    void initialize(TenantContext tenantContext, SecretsService secretsService);

    /**
     * Returns the tool providers contributed by this plugin.
     * Called after {@link #initialize(TenantContext, SecretsService)}.
     *
     * @return List of tool providers (may be empty if initialization failed gracefully)
     */
    List<ToolProvider> getToolProviders();

    /**
     * Checks if this plugin is healthy and ready to serve requests.
     *
     * @return true if the plugin is operational
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * Shuts down the plugin, releasing resources.
     */
    default void shutdown() {
        // Default no-op
    }
}