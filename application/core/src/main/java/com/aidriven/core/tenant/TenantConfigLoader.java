package com.aidriven.core.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads tenant configurations from environment variables or JSON.
 *
 * <p>Supports two loading strategies:
 * <ol>
 *   <li>Single-tenant: reads individual env vars (JIRA_SECRET_ARN, etc.) — backward compatible</li>
 *   <li>Multi-tenant: reads TENANTS_CONFIG env var (JSON array of tenant configs)</li>
 * </ol>
 *
 * <p>Example TENANTS_CONFIG:
 * <pre>{@code
 * [
 *   {
 *     "tenantId": "acme",
 *     "tenantName": "Acme Corp",
 *     "jiraSecretArn": "arn:aws:...",
 *     "bitbucketSecretArn": "arn:aws:...",
 *     "defaultPlatform": "BITBUCKET",
 *     "enabledTools": ["monitoring"],
 *     "triggerLabels": ["ai-generate"]
 *   }
 * ]
 * }</pre>
 */
@Slf4j
public class TenantConfigLoader {

    private static final String TENANTS_CONFIG_ENV = "TENANTS_CONFIG";
    private static final String DEFAULT_TENANT_ID_ENV = "DEFAULT_TENANT_ID";

    private final ObjectMapper objectMapper;

    public TenantConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Loads tenant configurations from environment and registers them in the registry.
     *
     * @param registry The registry to populate
     * @param jiraSecretArn       Fallback Jira secret ARN (from AppConfig)
     * @param bitbucketSecretArn  Fallback Bitbucket secret ARN (from AppConfig)
     * @param gitHubSecretArn     Fallback GitHub secret ARN (from AppConfig)
     * @param claudeSecretArn     Fallback Claude secret ARN (from AppConfig)
     * @param defaultPlatform     Fallback default platform
     * @param defaultWorkspace    Fallback default workspace
     * @param defaultRepo         Fallback default repo
     */
    public void loadInto(TenantRegistry registry,
                         String jiraSecretArn, String bitbucketSecretArn,
                         String gitHubSecretArn, String claudeSecretArn,
                         String defaultPlatform, String defaultWorkspace, String defaultRepo) {

        String tenantsConfigJson = System.getenv(TENANTS_CONFIG_ENV);

        if (tenantsConfigJson != null && !tenantsConfigJson.isBlank() && !"[]".equals(tenantsConfigJson.trim())) {
            loadMultiTenant(registry, tenantsConfigJson);
        } else {
            loadSingleTenant(registry, jiraSecretArn, bitbucketSecretArn, gitHubSecretArn,
                    claudeSecretArn, defaultPlatform, defaultWorkspace, defaultRepo);
        }
    }

    /**
     * Loads multi-tenant configuration from JSON.
     */
    @SuppressWarnings("unchecked")
    private void loadMultiTenant(TenantRegistry registry, String json) {
        try {
            List<Map<String, Object>> configs = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> cfg : configs) {
                TenantConfig tenant = mapToTenantConfig(cfg);
                registry.register(tenant);
            }
            log.info("Loaded {} tenants from TENANTS_CONFIG", configs.size());
        } catch (Exception e) {
            log.error("Failed to parse TENANTS_CONFIG: {}", e.getMessage(), e);
        }
    }

    /**
     * Creates a single default tenant from individual env vars (backward-compatible mode).
     */
    private void loadSingleTenant(TenantRegistry registry,
                                   String jiraSecretArn, String bitbucketSecretArn,
                                   String gitHubSecretArn, String claudeSecretArn,
                                   String defaultPlatform, String defaultWorkspace, String defaultRepo) {
        String tenantId = getEnv(DEFAULT_TENANT_ID_ENV, "default");

        TenantConfig tenant = TenantConfig.builder()
                .tenantId(tenantId)
                .tenantName("Default Tenant")
                .jiraSecretArn(jiraSecretArn)
                .bitbucketSecretArn(bitbucketSecretArn)
                .gitHubSecretArn(gitHubSecretArn)
                .claudeSecretArn(claudeSecretArn)
                .defaultPlatform(defaultPlatform != null ? defaultPlatform : "BITBUCKET")
                .defaultWorkspace(defaultWorkspace)
                .defaultRepo(defaultRepo)
                .build();

        registry.register(tenant);
        log.info("Loaded single-tenant config (tenantId={})", tenantId);
    }

    @SuppressWarnings("unchecked")
    private TenantConfig mapToTenantConfig(Map<String, Object> cfg) {
        TenantConfig.TenantConfigBuilder builder = TenantConfig.builder()
                .tenantId(getString(cfg, "tenantId"))
                .tenantName(getString(cfg, "tenantName"))
                .jiraSecretArn(getString(cfg, "jiraSecretArn"))
                .bitbucketSecretArn(getString(cfg, "bitbucketSecretArn"))
                .gitHubSecretArn(getString(cfg, "gitHubSecretArn"))
                .claudeSecretArn(getString(cfg, "claudeSecretArn"))
                .defaultPlatform(getStringOrDefault(cfg, "defaultPlatform", "BITBUCKET"))
                .defaultWorkspace(getString(cfg, "defaultWorkspace"))
                .defaultRepo(getString(cfg, "defaultRepo"))
                .branchPrefix(getStringOrDefault(cfg, "branchPrefix", "ai/"))
                .guardrailsEnabled(getBooleanOrDefault(cfg, "guardrailsEnabled", true))
                .maxAgentTurns(getIntOrDefault(cfg, "maxAgentTurns", 10))
                .tokenBudgetPerTicket(getIntOrDefault(cfg, "tokenBudgetPerTicket", 200_000))
                .mcpServersConfig(getStringOrDefault(cfg, "mcpServersConfig", "[]"));

        Object enabledToolsObj = cfg.get("enabledTools");
        if (enabledToolsObj instanceof List) {
            builder.enabledTools(Set.copyOf((List<String>) enabledToolsObj));
        }

        Object triggerLabelsObj = cfg.get("triggerLabels");
        if (triggerLabelsObj instanceof List) {
            builder.triggerLabels((List<String>) triggerLabelsObj);
        }

        return builder.build();
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        String val = getString(map, key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defaultValue;
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    private String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
