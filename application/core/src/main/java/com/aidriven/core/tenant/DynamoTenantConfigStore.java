package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB-backed tenant configuration store.
 *
 * <p>Key schema (uses existing single-table design):
 * <ul>
 *   <li>PK: TENANT#{tenantId}</li>
 *   <li>SK: CONFIG</li>
 * </ul>
 */
@Slf4j
public class DynamoTenantConfigStore implements TenantConfigStore {

    private static final String SK_CONFIG = "CONFIG";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoTenantConfigStore(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Optional<String> getTenantConfig(String tenantId) {
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

            AttributeValue configAttr = response.item().get("configJson");
            return configAttr != null ? Optional.of(configAttr.s()) : Optional.empty();

        } catch (Exception e) {
            log.error("Failed to load tenant config for {}: {}", tenantId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public void saveTenantConfig(String tenantId, String configJson) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS("TENANT#" + tenantId));
        item.put("SK", AttributeValue.fromS(SK_CONFIG));
        item.put("configJson", AttributeValue.fromS(configJson));
        item.put("updatedAt", AttributeValue.fromS(Instant.now().toString()));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

        log.info("Saved tenant config for {}", tenantId);
    }

    @Override
    public void deleteTenantConfig(String tenantId) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.fromS("TENANT#" + tenantId),
                        "SK", AttributeValue.fromS(SK_CONFIG)))
                .build());

        log.info("Deleted tenant config for {}", tenantId);
    }
}