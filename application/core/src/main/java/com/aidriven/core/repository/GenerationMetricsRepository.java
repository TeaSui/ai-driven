package com.aidriven.core.repository;

import com.aidriven.core.model.GenerationMetrics;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for {@link GenerationMetrics} records stored in the shared
 * DynamoDB single-table (same table as {@code TicketState}).
 */
@Slf4j
public class GenerationMetricsRepository {

    private final DynamoDbTable<GenerationMetrics> table;

    public GenerationMetricsRepository(DynamoDbClient dynamoDbClient, String tableName) {
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
        this.table = enhanced.table(tableName,
                TableSchema.fromBean(GenerationMetrics.class));
    }

    /** Persist a metrics record. */
    public void save(GenerationMetrics metrics) {
        log.debug("Saving generation metrics for ticket: {}", metrics.getTicketKey());
        table.putItem(metrics);
    }

    /** Query all metrics for a given ticket key (sorted by timestamp). */
    public List<GenerationMetrics> findByTicketKey(String ticketKey) {
        String pk = "METRICS#" + ticketKey;
        List<GenerationMetrics> results = new ArrayList<>();
        table.query(QueryConditional.keyEqualTo(
                Key.builder().partitionValue(pk).build()))
                .items()
                .forEach(results::add);
        return results;
    }

    /** Load a specific metrics record by PK + SK. */
    public GenerationMetrics findByKey(String pk, String sk) {
        return table.getItem(Key.builder()
                .partitionValue(pk)
                .sortValue(sk)
                .build());
    }
}
