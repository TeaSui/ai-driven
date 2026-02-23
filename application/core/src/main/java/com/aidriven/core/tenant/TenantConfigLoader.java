package com.aidriven.core.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;

/**
 * Loads tenant configurations from DynamoDB or environment variables.
 *
 * <p>DynamoDB schema (single-table):
 * <ul>
 *   <li>PK: TENANT#{tenantId}</li>
 *   <li>SK: CONFIG</li>
 * </ul>
 *
 * <p>For single-tenant deployments, configuration can be loaded from
 * environment variables as a fallback.</p>
 */
@Slf4j
public class TenantConfigLoader {

    private static final String TENANT_PK_PREFIX = "TENANT#";
    private static final String CONFIG_SK = "CONFIG";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final ObjectMapper objectMapper;

    public TenantConfigLoader(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Loads all active tenant configurations from DynamoDB.
     *
     * @return List of tenant configurations
     */
    public List<TenantConfig> loadAll() {
        try {
            ScanResponse response = dynamoDbClient.scan(ScanRequest.builder()
                    .tableName(tableName)
                    .filterExpression("begins_with(PK, :prefix) AND SK = :sk AND #active = :true")
                    .expressionAttributeNames(Map.of("#active", "active"))
                    .expressionAttributeValues(Map.of(
                            ":prefix", AttributeValue.fromS(TENANT_PK_PREFIX),
                            ":sk", AttributeValue.fromS(CONFIG_SK),
                            ":true", AttributeValue.fromBool(true)))
                    .build());

            List<TenantConfig> configs = new ArrayList<>();
            for (Map<String, AttributeValue> item : response.items()) {
                try {
                    TenantConfig config = fromDynamoItem(item);
                    configs.add(config);
                } catch (Exception e) {
                    log.warn("Failed to parse tenant config from DynamoDB item: {}", e.getMessage());
                }
            }

            log.info("Loaded {} tenant configurations from DynamoDB", configs.size());
            return configs;

        } catch (Exception e) {
            log.error("Failed to load tenant configurations from DynamoDB: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Loads a single tenant configuration by ID.
     *
     * @param tenantId The tenant identifier
     * @return Optional tenant configuration
     */
    public Optional<TenantConfig> load(String tenantId) {
        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS(TENANT_PK_PREFIX + tenantId),
                            "SK", AttributeValue.fromS(CONFIG_SK)))
                    .build());

            if (!response.hasItem() || response.item().isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(fromDynamoItem(response.item()));

        } catch (Exception e) {
            log.error("Failed to load tenant config for {}: {}", tenantId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Saves a tenant configuration to DynamoDB.
     *
     * @param config The tenant configuration to save
     */
    public void save(TenantConfig config) {
        Map<String, AttributeValue> item = toDynamoItem(config);
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
        log.info("Saved tenant config for: {}", config.getTenantId());
    }

    /**
     * Creates a TenantConfig from environment variables.
     * Used for single-tenant deployments where DynamoDB is not needed.
     *
     * @param tenantId The tenant ID to assign
     * @return TenantConfig populated from environment variables
     */
    public static TenantConfig fromEnvironment(String tenantId) {
        return TenantConfig.builder()
                .tenantId(tenantId)
                .tenantName(getEnv("TENANT_NAME", tenantId))
                .defaultPlatform(getEnv("DEFAULT_PLATFORM", "BITBUCKET"))
                .defaultWorkspace(System.getenv("DEFAULT_WORKSPACE"))
                .defaultRepo(System.getenv("DEFAULT_REPO"))
                .jiraSecretArn(System.getenv("JIRA_SECRET_ARN"))
                .bitbucketSecretArn(System.getenv("BITBUCKET_SECRET_ARN"))
                .githubSecretArn(System.getenv("GITHUB_SECRET_ARN"))
                .claudeSecretArn(System.getenv("CLAUDE_SECRET_ARN"))
                .dynamoDbTableName(System.getenv("DYNAMODB_TABLE_NAME"))
                .codeContextBucket(System.getenv("CODE_CONTEXT_BUCKET"))
                .stateMachineArn(System.getenv("STATE_MACHINE_ARN"))
                .agentQueueUrl(System.getenv("AGENT_QUEUE_URL"))
                .triggerLabel(getEnv("TRIGGER_LABEL", "ai-generate"))
                .agentTriggerPrefix(getEnv("AGENT_TRIGGER_PREFIX", "@ai"))
                .agentTokenBudget(getIntEnv("AGENT_TOKEN_BUDGET", 50_000))
                .agentMaxTurns(getIntEnv("AGENT_MAX_TURNS", 10))
                .guardrailsEnabled(Boolean.parseBoolean(getEnv("AGENT_GUARDRAILS_ENABLED", "true")))
                .mcpServersConfig(getEnv("MCP_SERVERS_CONFIG", "[]"))
                .active(true)
                .enabledPlugins(new java.util.HashSet<>())
                .metadata(new java.util.HashMap<>())
                .build();
    }

    private TenantConfig fromDynamoItem(Map<String, AttributeValue> item) throws Exception {
        String tenantId = item.get("PK").s().substring(TENANT_PK_PREFIX.length());

        Set<String> enabledPlugins = new HashSet<>();
        if (item.containsKey("enabledPlugins") && item.get("enabledPlugins").ss() != null) {
            enabledPlugins.addAll(item.get("enabledPlugins").ss());
        }

        Map<String, String> metadata = new HashMap<>();
        if (item.containsKey("metadata") && item.get("metadata").m() != null) {
            item.get("metadata").m().forEach((k, v) -> metadata.put(k, v.s()));
        }

        return TenantConfig.builder()
                .tenantId(tenantId)
                .tenantName(getString(item, "tenantName", tenantId))
                .defaultPlatform(getString(item, "defaultPlatform", "BITBUCKET"))
                .defaultWorkspace(getString(item, "defaultWorkspace", null))
                .defaultRepo(getString(item, "defaultRepo", null))
                .jiraSecretArn(getString(item, "jiraSecretArn", null))
                .bitbucketSecretArn(getString(item, "bitbucketSecretArn", null))
                .githubSecretArn(getString(item, "githubSecretArn", null))
                .claudeSecretArn(getString(item, "claudeSecretArn", null))
                .dynamoDbTableName(getString(item, "dynamoDbTableName", null))
                .codeContextBucket(getString(item, "codeContextBucket", null))
                .stateMachineArn(getString(item, "stateMachineArn", null))
                .agentQueueUrl(getString(item, "agentQueueUrl", null))
                .triggerLabel(getString(item, "triggerLabel", "ai-generate"))
                .agentTriggerPrefix(getString(item, "agentTriggerPrefix", "@ai"))
                .agentTokenBudget(getInt(item, "agentTokenBudget", 50_000))
                .agentMaxTurns(getInt(item, "agentMaxTurns", 10))
                .guardrailsEnabled(getBool(item, "guardrailsEnabled", true))
                .mcpServersConfig(getString(item, "mcpServersConfig", "[]"))
                .active(getBool(item, "active", true))
                .enabledPlugins(enabledPlugins)
                .metadata(metadata)
                .build();
    }

    private Map<String, AttributeValue> toDynamoItem(TenantConfig config) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(TENANT_PK_PREFIX + config.getTenantId()));
        item.put("SK", AttributeValue.fromS(CONFIG_SK));
        item.put("tenantName", AttributeValue.fromS(config.getTenantName() != null ? config.getTenantName() : config.getTenantId()));
        item.put("active", AttributeValue.fromBool(config.isActive()));
        item.put("guardrailsEnabled", AttributeValue.fromBool(config.isGuardrailsEnabled()));
        item.put("agentTokenBudget", AttributeValue.fromN(String.valueOf(config.effectiveAgentTokenBudget())));
        item.put("agentMaxTurns", AttributeValue.fromN(String.valueOf(config.effectiveAgentMaxTurns())));

        if (config.getDefaultPlatform() != null) item.put("defaultPlatform", AttributeValue.fromS(config.getDefaultPlatform()));
        if (config.getDefaultWorkspace() != null) item.put("defaultWorkspace", AttributeValue.fromS(config.getDefaultWorkspace()));
        if (config.getDefaultRepo() != null) item.put("defaultRepo", AttributeValue.fromS(config.getDefaultRepo()));
        if (config.getJiraSecretArn() != null) item.put("jiraSecretArn", AttributeValue.fromS(config.getJiraSecretArn()));
        if (config.getBitbucketSecretArn() != null) item.put("bitbucketSecretArn", AttributeValue.fromS(config.getBitbucketSecretArn()));
        if (config.getGithubSecretArn() != null) item.put("githubSecretArn", AttributeValue.fromS(config.getGithubSecretArn()));
        if (config.getClaudeSecretArn() != null) item.put("claudeSecretArn", AttributeValue.fromS(config.getClaudeSecretArn()));
        if (config.getDynamoDbTableName() != null) item.put("dynamoDbTableName", AttributeValue.fromS(config.getDynamoDbTableName()));
        if (config.getCodeContextBucket() != null) item.put("codeContextBucket", AttributeValue.fromS(config.getCodeContextBucket()));
        if (config.getStateMachineArn() != null) item.put("stateMachineArn", AttributeValue.fromS(config.getStateMachineArn()));
        if (config.getAgentQueueUrl() != null) item.put("agentQueueUrl", AttributeValue.fromS(config.getAgentQueueUrl()));
        if (config.getTriggerLabel() != null) item.put("triggerLabel", AttributeValue.fromS(config.getTriggerLabel()));
        if (config.getAgentTriggerPrefix() != null) item.put("agentTriggerPrefix", AttributeValue.fromS(config.getAgentTriggerPrefix()));
        if (config.getMcpServersConfig() != null) item.put("mcpServersConfig", AttributeValue.fromS(config.getMcpServersConfig()));

        if (config.getEnabledPlugins() != null && !config.getEnabledPlugins().isEmpty()) {
            item.put("enabledPlugins", AttributeValue.fromSs(new ArrayList<>(config.getEnabledPlugins())));
        }

        if (config.getMetadata() != null && !config.getMetadata().isEmpty()) {
            Map<String, AttributeValue> metaMap = new HashMap<>();
            config.getMetadata().forEach((k, v) -> metaMap.put(k, AttributeValue.fromS(v)));
            item.put("metadata", AttributeValue.fromM(metaMap));
        }

        return item;
    }

    private static String getString(Map<String, AttributeValue> item, String key, String defaultValue) {
        AttributeValue val = item.get(key);
        return (val != null && val.s() != null) ? val.s() : defaultValue;
    }

    private static int getInt(Map<String, AttributeValue> item, String key, int defaultValue) {
        AttributeValue val = item.get(key);
        if (val != null && val.n() != null) {
            try { return Integer.parseInt(val.n()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static boolean getBool(Map<String, AttributeValue> item, String key, boolean defaultValue) {
        AttributeValue val = item.get(key);
        return (val != null && val.bool() != null) ? val.bool() : defaultValue;
    }

    private static String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private static int getIntEnv(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
