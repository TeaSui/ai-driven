package com.aidriven.contracts.plugin;

import java.util.List;

/**
 * Describes a plugin that can be loaded into the system.
 * <p>
 * Plugins are discovered via Java's {@link java.util.ServiceLoader} mechanism.
 * Each plugin JAR includes a META-INF/services file pointing to its
 * PluginDescriptor implementation.
 * </p>
 *
 * <p>
 * A plugin can provide:
 * <ul>
 *   <li>Source control integrations (GitLab, Azure DevOps, etc.)</li>
 *   <li>Issue tracker integrations (Linear, Notion, etc.)</li>
 *   <li>AI model providers (OpenAI, Bedrock, local models)</li>
 *   <li>Tool providers (monitoring, messaging, data, etc.)</li>
 * </ul>
 * </p>
 */
public interface PluginDescriptor {

    /**
     * Unique identifier for this plugin (e.g., "gitlab-integration").
     */
    String id();

    /**
     * Human-readable name (e.g., "GitLab Source Control").
     */
    String name();

    /**
     * Plugin version (e.g., "1.0.0").
     */
    String version();

    /**
     * The types of integrations this plugin provides.
     */
    List<PluginCapability> capabilities();

    /**
     * Called during plugin initialization.
     * Plugins should register their implementations here.
     *
     * @param registry The plugin registry to register implementations with
     */
    void initialize(PluginRegistry registry);

    /**
     * Called during shutdown for cleanup.
     */
    default void shutdown() {
        // Default no-op
    }
}
