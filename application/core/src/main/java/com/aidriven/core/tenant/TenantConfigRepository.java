package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for managing {@link TenantConfig} records in DynamoDB.
 *
 * <p>Uses the existing single-table design with TENANT# prefix to isolate
 * tenant configuration from other data types.</p>
 */
@Slf4j
public class TenantConfigRepository {

    private final DynamoDbTable<TenantConfig> table;

    public TenantConfigRepository(DynamoDbClient dynamoDbClient, String tableName) {
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(TenantConfig.class));
    }

    /**
     * Saves or updates a tenant configuration.
     */
    public void save(TenantConfig config) {
        config.setUpdatedAt(Instant.now());
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(Instant.now());
        }
        table.putItem(config);
        log.info("Saved tenant config: tenantId={} plan={}", config.getTenantId(), config.getPlan());
    }

    /**
     * Retrieves a tenant configuration by tenant ID.
     *
     * @param tenantId The tenant identifier
     * @return The tenant config, or empty if not found
     */
    public Optional<TenantConfig> findByTenantId(String tenantId) {
        Key key = Key.builder()
                .partitionValue(TenantConfig.createPk(tenantId))
                .sortValue(TenantConfig.CONFIG_SK)
                .build();
        return Optional.ofNullable(table.getItem(key));
    }

    /**
     * Deletes a tenant configuration.
     *
     * @param tenantId The tenant identifier
     */
    public void delete(String tenantId) {
        Key key = Key.builder()
                .partitionValue(TenantConfig.createPk(tenantId))
                .sortValue(TenantConfig.CONFIG_SK)
                .build();
        table.deleteItem(key);
        log.info("Deleted tenant config: tenantId={}", tenantId);
    }

    /**
     * Checks if a tenant exists and is active.
     *
     * @param tenantId The tenant identifier
     * @return true if the tenant exists and is active
     */
    public boolean isActiveTenant(String tenantId) {
        return findByTenantId(tenantId)
                .map(TenantConfig::isActive)
                .orElse(false);
    }
}
