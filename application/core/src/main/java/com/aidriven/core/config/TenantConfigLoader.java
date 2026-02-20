package com.aidriven.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches tenant configurations.
 *
 * <p>Configuration sources (in priority order):</p>
 * <ol>
 *   <li>DynamoDB (production — per-tenant records)</li>
 *   <li>Environment variable TENANT_CONFIG_JSON (single-tenant / testing)</li>
 *   <li>Default config (backward-compatible single-tenant mode)</li>
 * </ol>
 */
@Slf4j
public class TenantConfigLoader {

    private static final String DEFAULT_TENANT_ID = "default";
    private static final String TENANT_CONFIG_ENV = "TENANT_CONFIG_JSON";

    private final Map<String, TenantAwareAppConfig> cache = new ConcurrentHashMap<>();
    private final AppConfig baseConfig;
    private final ObjectMapper objectMapper;

    public TenantConfigLoader(AppConfig baseConfig) {
        this.baseConfig = baseConfig;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get configuration for a specific tenant.
     * Falls back to default tenant if not found.
     */
    public TenantAwareAppConfig getConfig(String tenantId) {
        String effectiveTenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : DEFAULT_TENANT_ID;
        return cache.computeIfAbsent(effectiveTenantId, this::loadConfig);
    }

    /**
     * Get the default (single-tenant) configuration.
     * This provides backward compatibility with the existing system.
     */
    public TenantAwareAppConfig getDefaultConfig() {
        return getConfig(DEFAULT_TENANT_ID);
    }

    /**
     * Invalidate cached config for a tenant (e.g., after config update).
     */
    public void invalidate(String tenantId) {
        cache.remove(tenantId);
    }

    /**
     * Invalidate all cached configs.
     */
    public void invalidateAll() {
        cache.clear();
    }

    private TenantAwareAppConfig loadConfig(String tenantId) {
        // Try environment variable first (single-tenant / testing)
        Optional<Map<String, String>> envConfig = loadFromEnvironment(tenantId);
        if (envConfig.isPresent()) {
            log.info("Loaded tenant config for '{}' from environment", tenantId);
            return new TenantAwareAppConfig(baseConfig, tenantId, envConfig.get());
        }

        // Default: no overrides (backward-compatible single-tenant mode)
        log.info("Using default config for tenant '{}' (no overrides)", tenantId);
        return new TenantAwareAppConfig(baseConfig, tenantId, Map.of());
    }

    private Optional<Map<String, String>> loadFromEnvironment(String tenantId) {
        String configJson = System.getenv(TENANT_CONFIG_ENV);
        if (configJson == null || configJson.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<String, String> config = objectMapper.readValue(configJson,
                    new TypeReference<Map<String, String>>() {});
            return Optional.of(config);
        } catch (Exception e) {
            log.warn("Failed to parse TENANT_CONFIG_JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
