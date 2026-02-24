package com.aidriven.core.plugin;

import java.util.List;
import java.util.Map;

/**
 * Describes a plugin that can be registered for a tenant.
 *
 * <p>Plugins extend the workflow automation system with additional
 * capabilities (e.g., monitoring, messaging, data access).
 * Each plugin maps to a {@link com.aidriven.core.agent.tool.ToolProvider}
 * implementation.
 *
 * @param namespace       Unique plugin namespace (e.g., "monitoring", "messaging")
 * @param displayName     Human-readable plugin name
 * @param description     What this plugin does
 * @param version         Plugin version
 * @param requiredSecrets Secret ARN keys required by this plugin
 * @param defaultConfig   Default configuration values
 * @param capabilities    List of capability names this plugin provides
 */
public record PluginDescriptor(
        String namespace,
        String displayName,
        String description,
        String version,
        List<String> requiredSecrets,
        Map<String, Object> defaultConfig,
        List<String> capabilities) {

    /**
     * Creates a minimal plugin descriptor.
     */
    public static PluginDescriptor of(String namespace, String displayName, String description) {
        return new PluginDescriptor(
                namespace, displayName, description, "1.0.0",
                List.of(), Map.of(), List.of());
    }

    /**
     * Validates that required fields are present.
     *
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Plugin namespace must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Plugin displayName must not be blank for: " + namespace);
        }
    }
}
