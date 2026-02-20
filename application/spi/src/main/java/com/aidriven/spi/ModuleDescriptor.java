package com.aidriven.spi;

import java.util.List;
import java.util.Map;

/**
 * Describes a pluggable module in the AI-Driven system.
 * Each module self-registers via {@link ModuleProvider} and declares
 * its capabilities, dependencies, and configuration requirements.
 *
 * <p>This enables dynamic composition of modules per tenant:
 * <ul>
 *   <li>Company A uses: jira + bitbucket + claude</li>
 *   <li>Company B uses: jira + github + claude + datadog</li>
 *   <li>Company C uses: linear + github + claude + slack</li>
 * </ul>
 *
 * @param id              Unique module identifier (e.g., "jira-client", "github-client")
 * @param name            Human-readable name
 * @param version         Module version
 * @param category        Module category for grouping
 * @param description     What this module provides
 * @param requiredConfigs Configuration keys this module needs at runtime
 * @param dependencies    IDs of other modules this module depends on
 * @param capabilities    Capabilities this module provides (e.g., "issue-tracking", "source-control")
 */
public record ModuleDescriptor(
        String id,
        String name,
        String version,
        ModuleCategory category,
        String description,
        List<String> requiredConfigs,
        List<String> dependencies,
        List<String> capabilities) {

    public ModuleDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Module id must not be null or blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("Module category must not be null");
        }
        if (requiredConfigs == null) requiredConfigs = List.of();
        if (dependencies == null) dependencies = List.of();
        if (capabilities == null) capabilities = List.of();
    }

    /**
     * Builder for convenience.
     */
    public static Builder builder(String id, ModuleCategory category) {
        return new Builder(id, category);
    }

    public static class Builder {
        private final String id;
        private final ModuleCategory category;
        private String name;
        private String version = "1.0.0";
        private String description = "";
        private List<String> requiredConfigs = List.of();
        private List<String> dependencies = List.of();
        private List<String> capabilities = List.of();

        Builder(String id, ModuleCategory category) {
            this.id = id;
            this.category = category;
            this.name = id;
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder requiredConfigs(String... configs) { this.requiredConfigs = List.of(configs); return this; }
        public Builder dependencies(String... deps) { this.dependencies = List.of(deps); return this; }
        public Builder capabilities(String... caps) { this.capabilities = List.of(caps); return this; }

        public ModuleDescriptor build() {
            return new ModuleDescriptor(id, name, version, category, description,
                    requiredConfigs, dependencies, capabilities);
        }
    }
}
