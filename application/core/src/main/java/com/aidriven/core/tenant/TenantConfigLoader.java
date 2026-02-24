package com.aidriven.core.tenant;

import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Loads tenant configurations from various sources.
 *
 * <p>Supports loading from:
 * <ul>
 *   <li>Environment variable {@code TENANT_CONFIGS} (JSON array)</li>
 *   <li>AWS Secrets Manager (via secret ARN in {@code TENANT_CONFIG_SECRET_ARN})</li>
 *   <li>Programmatic registration (for testing)</li>
 * </ul>
 */
@Slf4j
public class TenantConfigLoader {

    private final ObjectMapper objectMapper;
    private final SecretsService secretsService;

    public TenantConfigLoader(ObjectMapper objectMapper, SecretsService secretsService) {
        this.objectMapper = objectMapper;
        this.secretsService = secretsService;
    }

    /**
     * Loads tenant configurations from the environment and registers them.
     *
     * @param registry The registry to populate
     */
    public void loadInto(TenantRegistry registry) {
        // 1. Try environment variable
        String envConfig = System.getenv("TENANT_CONFIGS");
        if (envConfig != null && !envConfig.isBlank() && !"[]".equals(envConfig.trim())) {
            loadFromJson(envConfig, registry);
            return;
        }

        // 2. Try Secrets Manager
        String secretArn = System.getenv("TENANT_CONFIG_SECRET_ARN");
        if (secretArn != null && !secretArn.isBlank() && secretsService != null) {
            try {
                String secretJson = secretsService.getSecret(secretArn);
                if (secretJson != null && !secretJson.isBlank()) {
                    loadFromJson(secretJson, registry);
                    return;
                }
            } catch (Exception e) {
                log.warn("Failed to load tenant configs from Secrets Manager: {}", e.getMessage());
            }
        }

        log.info("No tenant configurations found — running in single-tenant mode");
    }

    /**
     * Parses a JSON array of tenant configs and registers them.
     *
     * @param json     JSON array string
     * @param registry Target registry
     */
    void loadFromJson(String json, TenantRegistry registry) {
        try {
            List<Map<String, Object>> rawConfigs = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> raw : rawConfigs) {
                try {
                    TenantConfig config = mapToTenantConfig(raw);
                    registry.register(config);
                } catch (Exception e) {
                    log.error("Failed to parse tenant config entry: {} — {}", raw, e.getMessage());
                }
            }
            log.info("Loaded {} tenant configurations", rawConfigs.size());
        } catch (Exception e) {
            log.error("Failed to parse tenant configs JSON: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private TenantConfig mapToTenantConfig(Map<String, Object> raw) {
        TenantConfig.TenantConfigBuilder builder = TenantConfig.builder();

        if (raw.containsKey("tenantId")) builder.tenantId((String) raw.get("tenantId"));
        if (raw.containsKey("tenantName")) builder.tenantName((String) raw.get("tenantName"));
        if (raw.containsKey("platform")) builder.platform((String) raw.get("platform"));
        if (raw.containsKey("sourceControlSecretArn")) builder.sourceControlSecretArn((String) raw.get("sourceControlSecretArn"));
        if (raw.containsKey("issueTrackerSecretArn")) builder.issueTrackerSecretArn((String) raw.get("issueTrackerSecretArn"));
        if (raw.containsKey("aiSecretArn")) builder.aiSecretArn((String) raw.get("aiSecretArn"));
        if (raw.containsKey("defaultRepoOwner")) builder.defaultRepoOwner((String) raw.get("defaultRepoOwner"));
        if (raw.containsKey("defaultRepo")) builder.defaultRepo((String) raw.get("defaultRepo"));
        if (raw.containsKey("triggerLabel")) builder.triggerLabel((String) raw.get("triggerLabel"));
        if (raw.containsKey("agentTriggerPrefix")) builder.agentTriggerPrefix((String) raw.get("agentTriggerPrefix"));
        if (raw.containsKey("branchPrefix")) builder.branchPrefix((String) raw.get("branchPrefix"));
        if (raw.containsKey("claudeModel")) builder.claudeModel((String) raw.get("claudeModel"));
        if (raw.containsKey("agentMaxTurns")) builder.agentMaxTurns(((Number) raw.get("agentMaxTurns")).intValue());
        if (raw.containsKey("tokenBudgetPerTicket")) builder.tokenBudgetPerTicket(((Number) raw.get("tokenBudgetPerTicket")).intValue());
        if (raw.containsKey("guardrailsEnabled")) builder.guardrailsEnabled((Boolean) raw.get("guardrailsEnabled"));
        if (raw.containsKey("agentEnabled")) builder.agentEnabled((Boolean) raw.get("agentEnabled"));
        if (raw.containsKey("active")) builder.active((Boolean) raw.get("active"));

        if (raw.containsKey("enabledPlugins")) {
            Object plugins = raw.get("enabledPlugins");
            if (plugins instanceof List) {
                builder.enabledPlugins(new java.util.HashSet<>((List<String>) plugins));
            }
        }

        if (raw.containsKey("pluginConfig")) {
            Object pluginConfig = raw.get("pluginConfig");
            if (pluginConfig instanceof Map) {
                builder.pluginConfig((Map<String, Object>) pluginConfig);
            }
        }

        return builder.build();
    }
}
