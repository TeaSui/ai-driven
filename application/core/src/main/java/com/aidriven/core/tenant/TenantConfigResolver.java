package com.aidriven.core.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves tenant configuration from various sources.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>DynamoDB tenant table (multi-tenant mode)</li>
 *   <li>Environment variable JSON (single-tenant override)</li>
 *   <li>Default context (backward-compatible single-tenant)</li>
 * </ol>
 *
 * <p>Tenant configs are cached for the Lambda execution context lifetime.</p>
 */
@Slf4j
public class TenantConfigResolver {

    private static final String TENANT_CONFIG_ENV = "TENANT_CONFIG";
    private static final String MULTI_TENANT_ENABLED_ENV = "MULTI_TENANT_ENABLED";

    private final ObjectMapper objectMapper;
    private final Map<String, TenantContext> cache = new ConcurrentHashMap<>();
    private final TenantConfigStore configStore;

    /**
     * @param objectMapper JSON mapper
     * @param configStore  Backing store for tenant configs (nullable for single-tenant mode)
     */
    public TenantConfigResolver(ObjectMapper objectMapper, TenantConfigStore configStore) {
        this.objectMapper = objectMapper;
        this.configStore = configStore;
    }

    /** Single-tenant constructor (no backing store). */
    public TenantConfigResolver(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    /**
     * Resolves tenant context by ID.
     *
     * @param tenantId Tenant identifier (from webhook header, JWT claim, etc.)
     * @return Resolved tenant context
     */
    public TenantContext resolve(String tenantId) {
        if (!isMultiTenantEnabled()) {
            return resolveFromEnvOrDefault();
        }

        return cache.computeIfAbsent(tenantId, this::loadTenantConfig);
    }

    /**
     * Resolves the default tenant (single-tenant mode or fallback).
     */
    public TenantContext resolveDefault() {
        return resolveFromEnvOrDefault();
    }

    /**
     * Invalidates cached config for a tenant (e.g., after config update).
     */
    public void invalidate(String tenantId) {
        cache.remove(tenantId);
    }

    /**
     * Clears all cached tenant configs.
     */
    public void clearCache() {
        cache.clear();
    }

    private boolean isMultiTenantEnabled() {
        return Boolean.parseBoolean(System.getenv(MULTI_TENANT_ENABLED_ENV));
    }

    private TenantContext resolveFromEnvOrDefault() {
        String configJson = System.getenv(TENANT_CONFIG_ENV);
        if (configJson != null && !configJson.isBlank()) {
            try {
                return parseTenantConfig(configJson);
            } catch (Exception e) {
                log.warn("Failed to parse TENANT_CONFIG env var, using defaults: {}", e.getMessage());
            }
        }
        return TenantContext.defaultContext();
    }

    private TenantContext loadTenantConfig(String tenantId) {
        if (configStore == null) {
            log.warn("Multi-tenant enabled but no config store available, using defaults for tenant={}", tenantId);
            return TenantContext.defaultContext();
        }

        try {
            Optional<String> configJson = configStore.getTenantConfig(tenantId);
            if (configJson.isPresent()) {
                return parseTenantConfig(configJson.get());
            }
            log.warn("No config found for tenant={}, using defaults", tenantId);
            return TenantContext.defaultContext();
        } catch (Exception e) {
            log.error("Failed to load config for tenant={}: {}", tenantId, e.getMessage(), e);
            return TenantContext.defaultContext();
        }
    }

    @SuppressWarnings("unchecked")
    private TenantContext parseTenantConfig(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        String tenantId = root.path("tenantId").asText("default");
        String tenantName = root.path("tenantName").asText(tenantId);
        String environment = root.path("environment").asText("production");

        Map<String, String> secretArns = root.has("secretArns")
                ? objectMapper.convertValue(root.get("secretArns"), new TypeReference<Map<String, String>>() {})
                : Map.of();

        Map<String, String> configuration = root.has("configuration")
                ? objectMapper.convertValue(root.get("configuration"), new TypeReference<Map<String, String>>() {})
                : Map.of();

        Map<String, Boolean> enabledModules = root.has("enabledModules")
                ? objectMapper.convertValue(root.get("enabledModules"), new TypeReference<Map<String, Boolean>>() {})
                : Map.of();

        return new TenantContext(tenantId, tenantName, environment, secretArns, configuration, enabledModules);
    }
}