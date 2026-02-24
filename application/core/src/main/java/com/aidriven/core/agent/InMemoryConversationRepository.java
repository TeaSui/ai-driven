package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link ConversationRepository} for unit testing.
 * Messages are stored in a ConcurrentHashMap keyed by partition key.
 */
public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, List<ConversationMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void save(ConversationMessage message) {
        store.computeIfAbsent(message.getPk(), k -> new ArrayList<>()).add(message);
    }

    @Override
    public List<ConversationMessage> getConversation(String tenantId, String ticketKey) {
        String pk = ConversationMessage.createPk(tenantId, ticketKey);
        List<ConversationMessage> messages = store.getOrDefault(pk, List.of());
        return messages.stream()
                .sorted(Comparator.comparing(ConversationMessage::getSk))
                .collect(Collectors.toList());
    }

    @Override
    public int getTotalTokens(String tenantId, String ticketKey) {
        return getConversation(tenantId, ticketKey).stream()
                .mapToInt(ConversationMessage::getTokenCount)
                .sum();
    }

    @Override
    public void deleteConversation(String tenantId, String ticketKey) {
        String pk = ConversationMessage.createPk(tenantId, ticketKey);
        store.remove(pk);
    }
}
