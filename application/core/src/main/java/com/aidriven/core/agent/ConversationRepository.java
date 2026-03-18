package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;

import java.util.List;

/**
 * Repository for persisting and querying agent conversation messages.
 *
 * <p>
 * Implementations:
 * <ul>
 * <li>{@code DynamoConversationRepository} — DynamoDB single-table
 * (production)</li>
 * <li>{@code InMemoryConversationRepository} — in-memory (test source only)</li>
 * </ul>
 */
public interface ConversationRepository {

    /**
     * Persists a conversation message.
     */
    void save(ConversationMessage message);

    /**
     * Loads all messages for a ticket, sorted by sort key (chronological order).
     *
     * @param tenantId  The tenant ID
     * @param ticketKey Jira ticket key (e.g., "ONC-100")
     * @return Ordered list of conversation messages, empty if none
     */
    List<ConversationMessage> getConversation(String tenantId, String ticketKey);

    /**
     * Returns the total token count across all messages for a ticket.
     *
     * @param tenantId  The tenant ID
     * @param ticketKey Jira ticket key
     * @return Total tokens, 0 if no conversation exists
     */
    int getTotalTokens(String tenantId, String ticketKey);

    /**
     * Deletes all messages for a ticket's conversation.
     * Used when resetting conversation state.
     *
     * @param tenantId  The tenant ID
     * @param ticketKey Jira ticket key
     */
    void deleteConversation(String tenantId, String ticketKey);
}
