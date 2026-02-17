package com.aidriven.core.config;

import java.util.Map;
import java.util.Objects;

/**
 * Tenant-specific configuration that overrides global defaults.
 * Used when the platform operates in multi-tenant mode.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Tenant-specific value (from DynamoDB or tenant config)</li>
 *   <li>Global default (from environment variables / AppConfig)</li>
 * </ol>
 *
 * <p>In single-tenant mode (backward compatible), this simply wraps
 * the global AppConfig with no overrides.</p>
 */
public class TenantAwareConfig {

    private final AppConfig globalConfig;
    private final Map<String, String> tenantOverrides;

    /**
     * Creates a tenant-aware config with overrides.
     *
     * @param globalConfig    The global application config
     * @param tenantOverrides Tenant-specific overrides (may be empty)
     */
    public TenantAwareConfig(AppConfig globalConfig, Map<String, String> tenantOverrides) {
        this.globalConfig = Objects.requireNonNull(globalConfig);
        this.tenantOverrides = tenantOverrides != null ? Map.copyOf(tenantOverrides) : Map.of();
    }

    /**
     * Creates a config with no tenant overrides (single-tenant mode).
     */
    public static TenantAwareConfig singleTenant(AppConfig globalConfig) {
        return new TenantAwareConfig(globalConfig, Map.of());
    }

    /**
     * Gets a string config value with tenant override support.
     */
    public String getString(String key, String globalDefault) {
        String override = tenantOverrides.get(key);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return globalDefault;
    }

    /**
     * Gets an int config value with tenant override support.
     */
    public int getInt(String key, int globalDefault) {
        String override = tenantOverrides.get(key);
        if (override != null && !override.isBlank()) {
            try {
                return Integer.parseInt(override);
            } catch (NumberFormatException e) {
                return globalDefault;
            }
        }
        return globalDefault;
    }

    /**
     * Gets a boolean config value with tenant override support.
     */
    public boolean getBoolean(String key, boolean globalDefault) {
        String override = tenantOverrides.get(key);
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override);
        }
        return globalDefault;
    }

    /**
     * Checks if a tenant-specific override exists for the given key.
     */
    public boolean hasOverride(String key) {
        return tenantOverrides.containsKey(key)
                && tenantOverrides.get(key) != null
                && !tenantOverrides.get(key).isBlank();
    }

    /**
     * Returns the underlying global config.
     */
    public AppConfig getGlobalConfig() {
        return globalConfig;
    }

    /**
     * Returns all tenant overrides.
     */
    public Map<String, String> getTenantOverrides() {
        return tenantOverrides;
    }

    // --- Convenience accessors for common tenant-overridable settings ---

    public String getClaudeModel() {
        return getString("claude_model", globalConfig.getClaudeModel());
    }

    public int getClaudeMaxTokens() {
        return getInt("claude_max_tokens", globalConfig.getClaudeMaxTokens());
    }

    public double getClaudeTemperature() {
        String override = tenantOverrides.get("claude_temperature");
        if (override != null && !override.isBlank()) {
            try {
                return Double.parseDouble(override);
            } catch (NumberFormatException e) {
                return globalConfig.getClaudeTemperature();
            }
        }
        return globalConfig.getClaudeTemperature();
    }

    public String getDefaultPlatform() {
        return getString("default_platform", globalConfig.getDefaultPlatform());
    }

    public int getMaxContextForClaude() {
        return getInt("max_context_for_claude", globalConfig.getMaxContextForClaude());
    }

    public String getBranchPrefix() {
        return getString("branch_prefix", globalConfig.getBranchPrefix());
    }

    public int getAgentMaxTurns() {
        return getInt("agent_max_turns", globalConfig.getAgentConfig().maxTurns());
    }
}
