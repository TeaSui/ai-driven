package com.aidriven.core.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;

/**
 * DynamoDB-backed repository for tenant configurations.
 * Uses the existing single-table design.
 *
 * <p>Key schema:
 * <ul>
 *   <li>PK: TENANT#{tenantId}</li>
 *   <li>SK: CONFIG</li>
 * </ul>
 */
@Slf4j
public class DynamoTenantRepository {

    private static final String SK_CONFIG = "CONFIG";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final ObjectMapper objectMapper;

    public DynamoTenantRepository(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Saves a tenant configuration to DynamoDB.
     *
     * @param config The tenant configuration to save
     */
    public void save(TenantConfig config) {
        try {
            String configJson = objectMapper.writeValueAsString(config);
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("PK", AttributeValue.fromS("TENANT#" + config.getTenantId()));
            item.put("SK", AttributeValue.fromS(SK_CONFIG));
            item.put("tenantId", AttributeValue.fromS(config.getTenantId()));
            item.put("tenantName", AttributeValue.fromS(
                    config.getTenantName() != null ? config.getTenantName() : config.getTenantId()));
            item.put("configJson", AttributeValue.fromS(configJson));

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());

            log.info("Saved tenant config: {}", config.getTenantId());
        } catch (Exception e) {
            throw new RuntimeException("Failed to save tenant config: " + config.getTenantId(), e);
        }
    }

    /**
     * Loads a tenant configuration from DynamoDB.
     *
     * @param tenantId The tenant identifier
     * @return Optional containing the tenant config, or empty if not found
     */
    public Optional<TenantConfig> load(String tenantId) {
        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS("TENANT#" + tenantId),
                            "SK", AttributeValue.fromS(SK_CONFIG)))
                    .build());

            if (!response.hasItem() || response.item().isEmpty()) {
                return Optional.empty();
            }

            String configJson = response.item().get("configJson").s();
            TenantConfig config = objectMapper.readValue(configJson, TenantConfig.class);
            return Optional.of(config);

        } catch (Exception e) {
            log.error("Failed to load tenant config for {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Loads all tenant configurations from DynamoDB.
     * Uses a scan with filter — suitable for small tenant counts.
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
                            ":prefix", AttributeValue.fromS("TENANT#"),
                            ":sk", AttributeValue.fromS(SK_CONFIG)))
                    .build());

            for (Map<String, AttributeValue> item : response.items()) {
                try {
                    String configJson = item.get("configJson").s();
                    TenantConfig config = objectMapper.readValue(configJson, TenantConfig.class);
                    configs.add(config);
                } catch (Exception e) {
                    log.warn("Failed to parse tenant config item: {}", e.getMessage());
                }
            }

            log.info("Loaded {} tenant configurations", configs.size());
        } catch (Exception e) {
            log.error("Failed to load all tenant configs: {}", e.getMessage());
        }
        return configs;
    }

    /**
     * Deletes a tenant configuration.
     *
     * @param tenantId The tenant identifier to delete
     */
    public void delete(String tenantId) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.fromS("TENANT#" + tenantId),
                        "SK", AttributeValue.fromS(SK_CONFIG)))
                .build());
        log.info("Deleted tenant config: {}", tenantId);
    }
}
