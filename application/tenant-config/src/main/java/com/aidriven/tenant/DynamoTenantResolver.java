package com.aidriven.tenant;

import com.aidriven.contracts.config.TenantConfiguration;
import com.aidriven.contracts.config.TenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;

/**
 * DynamoDB-backed tenant resolver.
 * <p>
 * Stores tenant configurations in the existing single-table design:
 * <ul>
 *   <li>PK: TENANT#{tenantId}</li>
 *   <li>SK: CONFIG</li>
 * </ul>
 * </p>
 *
 * <p>
 * Also supports project-key-to-tenant mapping:
 * <ul>
 *   <li>PK: PROJECT#{projectKey}</li>
 *   <li>SK: TENANT_MAPPING</li>
 *   <li>tenantId: the resolved tenant ID</li>
 * </ul>
 * </p>
 */
@Slf4j
public class DynamoTenantResolver implements TenantResolver {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final ObjectMapper objectMapper;
    private final TenantConfiguration defaultConfig;

    /** Cache to avoid repeated DynamoDB reads within the same Lambda invocation. */
    private final Map<String, TenantConfiguration> cache = new HashMap<>();

    public DynamoTenantResolver(DynamoDbClient dynamoDbClient, String tableName,
            TenantConfiguration defaultConfig) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.objectMapper = new ObjectMapper();
        this.defaultConfig = defaultConfig;
    }

    @Override
    public Optional<TenantConfiguration> resolveByProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            return Optional.empty();
        }

        // Look up project → tenant mapping
        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("PROJECT#" + projectKey),
                            "SK", AttributeValue.fromS("TENANT_MAPPING")))
                    .build());

            if (!response.hasItem() || response.item().isEmpty()) {
                log.debug("No tenant mapping for project key: {}", projectKey);
                return Optional.empty();
            }

            String tenantId = response.item().get("tenantId").s();
            return resolveById(tenantId);

        } catch (Exception e) {
            log.warn("Failed to resolve tenant by project key {}: {}", projectKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<TenantConfiguration> resolveById(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }

        // Check cache first
        if (cache.containsKey(tenantId)) {
            return Optional.of(cache.get(tenantId));
        }

        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("TENANT#" + tenantId),
                            "SK", AttributeValue.fromS("CONFIG")))
                    .build());

            if (!response.hasItem() || response.item().isEmpty()) {
                log.debug("No tenant configuration for id: {}", tenantId);
                return Optional.empty();
            }

            TenantConfiguration config = parseTenantConfig(tenantId, response.item());
            cache.put(tenantId, config);
            return Optional.of(config);

        } catch (Exception e) {
            log.warn("Failed to resolve tenant by id {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public TenantConfiguration getDefault() {
        return defaultConfig;
    }

    @SuppressWarnings("unchecked")
    private TenantConfiguration parseTenantConfig(String tenantId, Map<String, AttributeValue> item) {
        String tenantName = getStringAttr(item, "tenantName", tenantId);

        TenantConfiguration.SourceControlConfig scConfig = null;
        if (item.containsKey("scPlatform")) {
            scConfig = new TenantConfiguration.SourceControlConfig(
                    getStringAttr(item, "scPlatform", "BITBUCKET"),
                    getStringAttr(item, "scSecretArn", null),
                    getStringAttr(item, "scWorkspace", null),
                    getStringAttr(item, "scRepo", null));
        }

        TenantConfiguration.IssueTrackerConfig itConfig = null;
        if (item.containsKey("itPlatform")) {
            itConfig = new TenantConfiguration.IssueTrackerConfig(
                    getStringAttr(item, "itPlatform", "JIRA"),
                    getStringAttr(item, "itSecretArn", null),
                    getStringAttr(item, "itBaseUrl", null));
        }

        TenantConfiguration.AiModelConfig aiConfig = null;
        if (item.containsKey("aiProvider")) {
            aiConfig = new TenantConfiguration.AiModelConfig(
                    getStringAttr(item, "aiProvider", "claude"),
                    getStringAttr(item, "aiSecretArn", null),
                    getStringAttr(item, "aiDefaultModel", "claude-sonnet-4-5"),
                    getIntAttr(item, "aiMaxTokens", 32768),
                    getDoubleAttr(item, "aiTemperature", 0.2));
        }

        List<String> enabledTools = item.containsKey("enabledTools")
                ? item.get("enabledTools").l().stream().map(AttributeValue::s).toList()
                : List.of();

        Map<String, String> customSettings = new HashMap<>();
        if (item.containsKey("customSettings") && item.get("customSettings").m() != null) {
            item.get("customSettings").m().forEach((k, v) -> customSettings.put(k, v.s()));
        }

        return new TenantConfiguration(
                tenantId, tenantName,
                scConfig, itConfig, aiConfig,
                enabledTools, customSettings);
    }

    private String getStringAttr(Map<String, AttributeValue> item, String key, String defaultValue) {
        AttributeValue val = item.get(key);
        return (val != null && val.s() != null) ? val.s() : defaultValue;
    }

    private int getIntAttr(Map<String, AttributeValue> item, String key, int defaultValue) {
        AttributeValue val = item.get(key);
        if (val != null && val.n() != null) {
            try {
                return Integer.parseInt(val.n());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double getDoubleAttr(Map<String, AttributeValue> item, String key, double defaultValue) {
        AttributeValue val = item.get(key);
        if (val != null && val.n() != null) {
            try {
                return Double.parseDouble(val.n());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
