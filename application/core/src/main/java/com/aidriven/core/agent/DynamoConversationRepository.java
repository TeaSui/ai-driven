package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Production implementation of {@link ConversationRepository} using DynamoDB.
 */
@Slf4j
public class DynamoConversationRepository implements ConversationRepository {

    private final DynamoDbTable<ConversationMessage> table;

    public DynamoConversationRepository(DynamoDbClient dynamoDbClient, String tableName) {
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(ConversationMessage.class));
    }

    @Override
    public void save(ConversationMessage message) {
        table.putItem(message);
        log.debug("Saved conversation message: {}/{}", message.getPk(), message.getSk());
    }

    @Override
    public List<ConversationMessage> getConversation(String tenantId, String ticketKey) {
        String pk = ConversationMessage.createPk(tenantId, ticketKey);

        List<ConversationMessage> recentMessages = table.query(r -> r
                .queryConditional(QueryConditional.sortBeginsWith(Key.builder()
                        .partitionValue(pk)
                        .sortValue("MSG#")
                        .build()))
                .scanIndexForward(false) // Newest first to get latest context
                .limit(100) // Limit per page to save RCUs
        ).items().stream()
                .limit(100) // Hard limit to prevent unbounded pagination
                .collect(Collectors.toList());

        java.util.Collections.reverse(recentMessages); // Restore chronological order
        return recentMessages;
    }

    @Override
    public int getTotalTokens(String tenantId, String ticketKey) {
        // Note: In a high-scale scenario, we might want to maintain a separate counter
        // or use a GSI with projection, but for agent conversations (typically < 50-100
        // items),
        // querying and summing is acceptable.
        return getConversation(tenantId, ticketKey).stream()
                .mapToInt(ConversationMessage::getTokenCount)
                .sum();
    }

    @Override
    public void deleteConversation(String tenantId, String ticketKey) {
        List<ConversationMessage> messages = getConversation(tenantId, ticketKey);

        for (ConversationMessage msg : messages) {
            table.deleteItem(Key.builder()
                    .partitionValue(msg.getPk())
                    .sortValue(msg.getSk())
                    .build());
        }
        log.info("Deleted {} conversation messages for ticket {}", messages.size(), ticketKey);
    }
}
