package com.aidriven.core.repository;

import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for managing ticket state in DynamoDB.
 */
@Slf4j
public class TicketStateRepository {

    private final DynamoDbTable<TicketState> table;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public TicketStateRepository(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(TicketState.class));
    }

    /**
     * Saves a ticket state.
     */
    public void save(TicketState state) {
        state.setUpdatedAt(Instant.now());
        if (state.getCreatedAt() == null) {
            state.setCreatedAt(Instant.now());
        }
        table.putItem(state);
        log.info("Saved ticket state: {} with status {}", state.getTicketId(), state.getStatus());
    }

    /**
     * Saves a ticket state only if it doesn't already exist.
     * Useful for idempotency checks.
     * 
     * @return true if saved, false if failed due to existing record
     */
    public boolean saveIfNotExists(TicketState state) {
        state.setUpdatedAt(Instant.now());
        if (state.getCreatedAt() == null) {
            state.setCreatedAt(Instant.now());
        }

        try {
            table.putItem(r -> r.item(state)
                    .conditionExpression(Expression.builder()
                            .expression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                            .build()));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /**
     * Gets the latest state for a ticket.
     */
    public Optional<TicketState> getLatestState(String tenantId, String ticketId) {
        String pk = TicketState.createPk(tenantId, ticketId);

        List<TicketState> states = table.query(r -> r
                .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(pk).build()))
                .scanIndexForward(false)
                .limit(1)).items().stream().toList();

        return states.isEmpty() ? Optional.empty() : Optional.of(states.getFirst());
    }

    /**
     * Updates the status of a ticket.
     */
    public TicketState updateStatus(String tenantId, String ticketId, String ticketKey, ProcessingStatus newStatus) {
        TicketState state = TicketState.forTicket(tenantId, ticketId, ticketKey, newStatus);
        save(state);
        return state;
    }

    /**
     * Gets a state by primary key.
     */
    public Optional<TicketState> get(String pk, String sk) {
        Key key = Key.builder()
                .partitionValue(pk)
                .sortValue(sk)
                .build();
        return Optional.ofNullable(table.getItem(key));
    }

    /**
     * Queries all states for a ticket.
     */
    public List<TicketState> getTicketHistory(String tenantId, String ticketId) {
        String pk = TicketState.createPk(tenantId, ticketId);

        return table.query(r -> r
                .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(pk).build()))).items()
                .stream().toList();
    }

    /**
     * Atomically increments the event count for a given tenant in a specific time
     * window.
     * Uses DynamoDB atomic ADD operation on a number attribute.
     *
     * @param tenantId           The tenant ID
     * @param windowId           The time window identifier (e.g., "2023-11-20T10"
     *                           for hourly)
     * @param expireEpochSeconds When the record should expire
     * @return The updated count
     */
    public long incrementTenantEventCount(String tenantId, String windowId, long expireEpochSeconds) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("PK", AttributeValue.builder().s("RATELIMIT#" + tenantId).build());
        key.put("SK", AttributeValue.builder().s("WINDOW#" + windowId).build());

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":val", AttributeValue.builder().n("1").build());
        expressionAttributeValues.put(":ttl", AttributeValue.builder().n(String.valueOf(expireEpochSeconds)).build());

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("ADD requestCount :val SET #ttl = :ttl")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(expressionAttributeValues)
                .returnValues(ReturnValue.UPDATED_NEW)
                .build();

        UpdateItemResponse response = dynamoDbClient.updateItem(request);
        return Long.parseLong(response.attributes().get("requestCount").n());
    }
}
