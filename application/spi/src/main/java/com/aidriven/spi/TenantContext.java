package com.aidriven.spi;

import java.util.*;

/**
 * Holds tenant-specific configuration and module selections.
 *
 * <p>In a multi-tenant SaaS deployment, each tenant (company) has:
 * <ul>
 *   <li>A unique tenant ID</li>
 *   <li>A set of enabled modules (e.g., "jira" + "github" + "claude")</li>
 *   <li>Module-specific configuration (API keys, URLs, etc.)</li>
 * </ul>
 *
 * <p>The platform uses TenantContext to determine which modules
 * to activate for each request.</p>
 */
public class TenantContext {

    private final String tenantId;
    private final String tenantName;
    private final Set<String> enabledModules;
    private final Map<String, Map<String, String>> moduleConfigs;

    private TenantContext(Builder builder) {
        this.tenantId = Objects.requireNonNull(builder.tenantId, "tenantId is required");
        this.tenantName = builder.tenantName != null ? builder.tenantName : builder.tenantId;
        this.enabledModules = Collections.unmodifiableSet(new LinkedHashSet<>(builder.enabledModules));
        this.moduleConfigs = Collections.unmodifiableMap(builder.moduleConfigs);
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTenantName() {
        return tenantName;
    }

    public Set<String> getEnabledModules() {
        return enabledModules;
    }

    /**
     * Returns configuration for a specific module.
     *
     * @param moduleId The module ID
     * @return Configuration map, empty if no config exists
     */
    public Map<String, String> getModuleConfig(String moduleId) {
        return moduleConfigs.getOrDefault(moduleId, Map.of());
    }

    /**
     * Checks if a module is enabled for this tenant.
     */
    public boolean isModuleEnabled(String moduleId) {
        return enabledModules.contains(moduleId);
    }

    /**
     * Returns all module configurations (for bulk initialization).
     */
    public Map<String, Map<String, String>> getAllModuleConfigs() {
        return moduleConfigs;
    }

    public static Builder builder(String tenantId) {
        return new Builder(tenantId);
    }

    @Override
    public String toString() {
        return "TenantContext{tenantId='" + tenantId + "', modules=" + enabledModules + "}";
    }

    public static class Builder {
        private final String tenantId;
        private String tenantName;
        private final Set<String> enabledModules = new LinkedHashSet<>();
        private final Map<String, Map<String, String>> moduleConfigs = new LinkedHashMap<>();

        private Builder(String tenantId) {
            this.tenantId = tenantId;
        }

        public Builder tenantName(String tenantName) {
            this.tenantName = tenantName;
            return this;
        }

        public Builder enableModule(String moduleId) {
            this.enabledModules.add(moduleId);
            return this;
        }

        public Builder enableModules(String... moduleIds) {
            this.enabledModules.addAll(Arrays.asList(moduleIds));
            return this;
        }

        public Builder moduleConfig(String moduleId, Map<String, String> config) {
            this.enabledModules.add(moduleId);
            this.moduleConfigs.put(moduleId, new LinkedHashMap<>(config));
            return this;
        }

        public TenantContext build() {
            return new TenantContext(this);
        }
    }
}
