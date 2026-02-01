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
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing ticket state in DynamoDB.
 */
@Slf4j
public class TicketStateRepository {
    
    private final DynamoDbTable<TicketState> table;
    
    public TicketStateRepository(DynamoDbClient dynamoDbClient, String tableName) {
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
    public Optional<TicketState> getLatestState(String ticketId) {
        String pk = TicketState.createPk(ticketId);
        
        List<TicketState> states = table.query(r -> r
                .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(pk).build()))
                .scanIndexForward(false)
                .limit(1)
        ).items().stream().toList();

        return states.isEmpty() ? Optional.empty() : Optional.of(states.getFirst());
    }
    
    /**
     * Updates the status of a ticket.
     */
    public TicketState updateStatus(String ticketId, String ticketKey, ProcessingStatus newStatus) {
        TicketState state = TicketState.forTicket(ticketId, ticketKey, newStatus);
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
    public List<TicketState> getTicketHistory(String ticketId) {
        String pk = TicketState.createPk(ticketId);
        
        return table.query(r -> r
                .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(pk).build()))
        ).items().stream().toList();
    }
}
