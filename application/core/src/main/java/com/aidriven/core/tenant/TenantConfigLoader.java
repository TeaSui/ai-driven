package com.aidriven.core.tenant;

import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads tenant configurations from various sources.
 *
 * <p>Supports loading from:
 * <ul>
 *   <li>Environment variable (JSON array of tenant configs)</li>
 *   <li>AWS Secrets Manager (for sensitive tenant credentials)</li>
 *   <li>Programmatic registration (for testing)</li>
 * </ul>
 *
 * <p>Environment variable format (TENANT_CONFIGS):
 * <pre>{@code
 * [
 *   {
 *     "tenantId": "acme-corp",
 *     "tenantName": "ACME Corporation",
 *     "jiraSecretArn": "arn:aws:secretsmanager:...",
 *     "sourceControlSecretArn": "arn:aws:secretsmanager:...",
 *     "defaultPlatform": "GITHUB",
 *     "enabledPlugins": ["monitoring", "messaging"],
 *     "tokenBudget": 300000,
 *     "maxTurns": 15,
 *     "guardrailsEnabled": true,
 *     "active": true
 *   }
 * ]
 * }</pre>
 */
@Slf4j
public class TenantConfigLoader {

    private static final String TENANT_CONFIGS_ENV = "TENANT_CONFIGS";
    private static final String DEFAULT_TENANT_ID_ENV = "DEFAULT_TENANT_ID";

    private final ObjectMapper objectMapper;
    private final SecretsService secretsService;

    public TenantConfigLoader(ObjectMapper objectMapper, SecretsService secretsService) {
        this.objectMapper = objectMapper;
        this.secretsService = secretsService;
    }

    /**
     * Loads tenant configurations from environment variables and populates the registry.
     *
     * @param registry The registry to populate
     * @return Number of tenants loaded
     */
    public int loadFromEnvironment(TenantRegistry registry) {
        String configJson = System.getenv(TENANT_CONFIGS_ENV);

        if (configJson == null || configJson.isBlank() || "[]".equals(configJson.trim())) {
            log.info("No TENANT_CONFIGS found, using single-tenant mode");
            return loadDefaultTenant(registry);
        }

        try {
            List<Map<String, Object>> rawConfigs = objectMapper.readValue(
                    configJson, new TypeReference<List<Map<String, Object>>>() {});

            List<TenantConfig> configs = new ArrayList<>();
            for (Map<String, Object> raw : rawConfigs) {
                try {
                    TenantConfig config = parseConfig(raw);
                    configs.add(config);
                } catch (Exception e) {
                    log.error("Failed to parse tenant config: {}", e.getMessage(), e);
                }
            }

            for (TenantConfig config : configs) {
                registry.register(config);
            }

            log.info("Loaded {} tenant configurations", configs.size());
            return configs.size();

        } catch (Exception e) {
            log.error("Failed to load TENANT_CONFIGS: {}", e.getMessage(), e);
            return loadDefaultTenant(registry);
        }
    }

    /**
     * Returns the default tenant ID from environment.
     */
    public String getDefaultTenantId() {
        String envValue = System.getenv(DEFAULT_TENANT_ID_ENV);
        return (envValue != null && !envValue.isBlank()) ? envValue : "default";
    }

    /**
     * Loads a single default tenant for single-tenant deployments.
     */
    private int loadDefaultTenant(TenantRegistry registry) {
        String defaultId = getDefaultTenantId();
        TenantConfig defaultConfig = TenantConfig.defaultTenant(defaultId);
        registry.register(defaultConfig);
        log.info("Registered default tenant: {}", defaultId);
        return 1;
    }

    /**
     * Parses a raw config map into a TenantConfig.
     */
    @SuppressWarnings("unchecked")
    private TenantConfig parseConfig(Map<String, Object> raw) {
        String tenantId = getString(raw, "tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }

        Object pluginsObj = raw.get("enabledPlugins");
        java.util.Set<String> enabledPlugins = new java.util.HashSet<>();
        if (pluginsObj instanceof List) {
            for (Object p : (List<?>) pluginsObj) {
                if (p != null) enabledPlugins.add(p.toString());
            }
        }

        Object flagsObj = raw.get("featureFlags");
        Map<String, Boolean> featureFlags = new java.util.HashMap<>();
        if (flagsObj instanceof Map) {
            ((Map<String, Object>) flagsObj).forEach((k, v) ->
                    featureFlags.put(k, Boolean.parseBoolean(v.toString())));
        }

        Object triggersObj = raw.get("triggerLabels");
        List<String> triggerLabels = new ArrayList<>();
        if (triggersObj instanceof List) {
            for (Object t : (List<?>) triggersObj) {
                if (t != null) triggerLabels.add(t.toString());
            }
        }

        return TenantConfig.builder()
                .tenantId(tenantId)
                .tenantName(getString(raw, "tenantName", tenantId))
                .jiraSecretArn(getString(raw, "jiraSecretArn"))
                .sourceControlSecretArn(getString(raw, "sourceControlSecretArn"))
                .claudeSecretArn(getString(raw, "claudeSecretArn"))
                .defaultPlatform(getString(raw, "defaultPlatform", "BITBUCKET"))
                .defaultWorkspace(getString(raw, "defaultWorkspace"))
                .defaultRepo(getString(raw, "defaultRepo"))
                .enabledPlugins(enabledPlugins)
                .featureFlags(featureFlags)
                .triggerLabels(triggerLabels)
                .tokenBudget(getInt(raw, "tokenBudget", 200_000))
                .maxTurns(getInt(raw, "maxTurns", 10))
                .guardrailsEnabled(getBoolean(raw, "guardrailsEnabled", true))
                .mcpServersConfig(getString(raw, "mcpServersConfig", "[]"))
                .active(getBoolean(raw, "active", true))
                .build();
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        String val = getString(map, key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val.toString());
    }
}
