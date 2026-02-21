package com.aidriven.core.plugin;

import java.util.Set;

/**
 * Describes a plugin module that can be dynamically loaded per tenant.
 * Each plugin maps to one or more tool provider namespaces.
 *
 * <p>Plugins are discovered via Java SPI ({@link java.util.ServiceLoader})
 * and registered based on tenant configuration.</p>
 *
 * @param id          Unique plugin identifier (e.g., "jira", "github", "datadog")
 * @param name        Human-readable name
 * @param version     Plugin version
 * @param namespaces  Tool provider namespaces this plugin contributes
 * @param description Brief description of what this plugin provides
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        Set<String> namespaces,
        String description) {

    public PluginDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Plugin id must not be null or blank");
        }
        if (namespaces == null || namespaces.isEmpty()) {
            throw new IllegalArgumentException("Plugin must declare at least one namespace");
        }
    }
}