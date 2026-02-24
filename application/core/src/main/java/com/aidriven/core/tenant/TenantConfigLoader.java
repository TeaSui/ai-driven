package com.aidriven.core.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;

/**
 * Loads tenant configurations from DynamoDB.
 *
 * <p>Key schema (uses existing single-table design):
 * <ul>
 *   <li>PK: TENANT#{tenantId}</li>
 *   <li>SK: CONFIG</li>
 * </ul>
 *
 * <p>Supports both single-tenant (reads one config) and
 * multi-tenant (scans all TENANT# records) modes.
 */
@Slf4j
public class TenantConfigLoader {

    private static final String PK_PREFIX = "TENANT#";
    private static final String SK_CONFIG = "CONFIG";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final ObjectMapper objectMapper;

    public TenantConfigLoader(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Loads a single tenant configuration from DynamoDB.
     *
     * @param tenantId The tenant identifier
     * @return Optional containing the config, or empty if not found
     */
    public Optional<TenantConfig> load(String tenantId) {
        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS(PK_PREFIX + tenantId),
                            "SK", AttributeValue.fromS(SK_CONFIG)))
                    .build());

            if (!response.hasItem() || response.item().isEmpty()) {
                log.debug("No config found for tenant: {}", tenantId);
                return Optional.empty();
            }

            TenantConfig config = fromDynamoItem(response.item());
            log.info("Loaded config for tenant: {}", tenantId);
            return Optional.of(config);

        } catch (Exception e) {
            log.error("Failed to load config for tenant {}: {}", tenantId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Saves a tenant configuration to DynamoDB.
     *
     * @param config The tenant configuration to save
     */
    public void save(TenantConfig config) {
        try {
            Map<String, AttributeValue> item = toDynamoItem(config);
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());
            log.info("Saved config for tenant: {}", config.getTenantId());
        } catch (Exception e) {
            log.error("Failed to save config for tenant {}: {}", config.getTenantId(), e.getMessage(), e);
            throw new RuntimeException("Failed to save tenant config", e);
        }
    }

    /**
     * Loads all tenant configurations from DynamoDB.
     * Uses a scan with filter — suitable for small numbers of tenants.
     *
     * @return List of all tenant configurations
     */
    public List<TenantConfig> loadAll() {
        List<TenantConfig> configs = new ArrayList<>();
        try {
            ScanResponse response = dynamoDbClient.scan(ScanRequest.builder()
                    .tableName(tableName)
                    .filterExpression("begins_with(PK, :prefix) AND SK = :sk")
                    .expressionAttributeValues(Map.of(
                            ":prefix", AttributeValue.fromS(PK_PREFIX),
                            ":sk", AttributeValue.fromS(SK_CONFIG)))
                    .build());

            for (Map<String, AttributeValue> item : response.items()) {
                try {
                    configs.add(fromDynamoItem(item));
                } catch (Exception e) {
                    log.warn("Failed to parse tenant config item: {}", e.getMessage());
                }
            }

            log.info("Loaded {} tenant configurations", configs.size());
        } catch (Exception e) {
            log.error("Failed to load all tenant configs: {}", e.getMessage(), e);
        }
        return configs;
    }

    private TenantConfig fromDynamoItem(Map<String, AttributeValue> item) throws Exception {
        TenantConfig.TenantConfigBuilder builder = TenantConfig.builder();

        getStr(item, "tenantId").ifPresent(builder::tenantId);
        getStr(item, "tenantName").ifPresent(builder::tenantName);
        getStr(item, "awsRegion").ifPresent(builder::awsRegion);
        getStr(item, "dynamoDbTableName").ifPresent(builder::dynamoDbTableName);
        getStr(item, "codeContextBucket").ifPresent(builder::codeContextBucket);
        getStr(item, "jiraSecretArn").ifPresent(builder::jiraSecretArn);
        getStr(item, "bitbucketSecretArn").ifPresent(builder::bitbucketSecretArn);
        getStr(item, "gitHubSecretArn").ifPresent(builder::gitHubSecretArn);
        getStr(item, "claudeSecretArn").ifPresent(builder::claudeSecretArn);
        getStr(item, "defaultPlatform").ifPresent(builder::defaultPlatform);
        getStr(item, "defaultWorkspace").ifPresent(builder::defaultWorkspace);
        getStr(item, "defaultRepo").ifPresent(builder::defaultRepo);
        getStr(item, "branchPrefix").ifPresent(builder::branchPrefix);
        getStr(item, "claudeModel").ifPresent(builder::claudeModel);
        getStr(item, "mcpServersConfig").ifPresent(builder::mcpServersConfig);

        if (item.containsKey("maxContextForClaude")) {
            builder.maxContextForClaude(Integer.parseInt(item.get("maxContextForClaude").n()));
        }

        if (item.containsKey("enabledPlugins") && item.get("enabledPlugins").ss() != null) {
            builder.enabledPlugins(new java.util.HashSet<>(item.get("enabledPlugins").ss()));
        }

        if (item.containsKey("featureFlags")) {
            String flagsJson = item.get("featureFlags").s();
            Map<String, Boolean> flags = objectMapper.readValue(flagsJson,
                    new TypeReference<Map<String, Boolean>>() {});
            builder.featureFlags(flags);
        }

        return builder.build();
    }

    private Map<String, AttributeValue> toDynamoItem(TenantConfig config) throws Exception {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(PK_PREFIX + config.getTenantId()));
        item.put("SK", AttributeValue.fromS(SK_CONFIG));

        putStr(item, "tenantId", config.getTenantId());
        putStr(item, "tenantName", config.getTenantName());
        putStr(item, "awsRegion", config.getAwsRegion());
        putStr(item, "dynamoDbTableName", config.getDynamoDbTableName());
        putStr(item, "codeContextBucket", config.getCodeContextBucket());
        putStr(item, "jiraSecretArn", config.getJiraSecretArn());
        putStr(item, "bitbucketSecretArn", config.getBitbucketSecretArn());
        putStr(item, "gitHubSecretArn", config.getGitHubSecretArn());
        putStr(item, "claudeSecretArn", config.getClaudeSecretArn());
        putStr(item, "defaultPlatform", config.getDefaultPlatform());
        putStr(item, "defaultWorkspace", config.getDefaultWorkspace());
        putStr(item, "defaultRepo", config.getDefaultRepo());
        putStr(item, "branchPrefix", config.getBranchPrefix());
        putStr(item, "claudeModel", config.getClaudeModel());
        putStr(item, "mcpServersConfig", config.getMcpServersConfig());

        item.put("maxContextForClaude",
                AttributeValue.fromN(String.valueOf(config.getMaxContextForClaude())));

        if (config.getEnabledPlugins() != null && !config.getEnabledPlugins().isEmpty()) {
            item.put("enabledPlugins",
                    AttributeValue.fromSs(new ArrayList<>(config.getEnabledPlugins())));
        }

        if (config.getFeatureFlags() != null && !config.getFeatureFlags().isEmpty()) {
            item.put("featureFlags",
                    AttributeValue.fromS(objectMapper.writeValueAsString(config.getFeatureFlags())));
        }

        return item;
    }

    private Optional<String> getStr(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        if (val == null || val.s() == null || val.s().isBlank()) return Optional.empty();
        return Optional.of(val.s());
    }

    private void putStr(Map<String, AttributeValue> item, String key, String value) {
        if (value != null && !value.isBlank()) {
            item.put(key, AttributeValue.fromS(value));
        }
    }
}
