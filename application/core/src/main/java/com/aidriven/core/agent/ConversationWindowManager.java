package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages conversation context window for the agent orchestrator.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Load conversation history from repository</li>
 * <li>Enforce token budget by pruning oldest messages</li>
 * <li>Always keep at least N recent messages (even if over budget)</li>
 * <li>Convert persisted messages to Claude API format</li>
 * </ul>
 *
 * <p>
 * Pruning strategy: keep the most recent {@code recentMessagesToKeep} messages
 * unconditionally. Then, working backwards from the remaining older messages,
 * include as many as fit within the remaining token budget.
 */
@Slf4j
public class ConversationWindowManager {

    private final ConversationRepository repository;
    private final int tokenBudget;
    private final int recentMessagesToKeep;
    private final ObjectMapper objectMapper;

    public ConversationWindowManager(ConversationRepository repository, int tokenBudget, int recentMessagesToKeep) {
        this.repository = repository;
        this.tokenBudget = tokenBudget;
        this.recentMessagesToKeep = recentMessagesToKeep;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Builds the message list for the Claude API call, applying token window
     * pruning.
     *
     * @param ticketKey Jira ticket key
     * @return Ordered list of messages in Claude API format, pruned to fit budget
     */
    public List<Map<String, Object>> buildMessages(String ticketKey) {
        List<ConversationMessage> allMessages = repository.getConversation(ticketKey);
        if (allMessages.isEmpty()) {
            return List.of();
        }

        List<ConversationMessage> selected = selectWithinBudget(allMessages);
        return toApiFormat(selected);
    }

    /**
     * Saves a new message and builds the pruned message list in one operation.
     *
     * @param ticketKey Jira ticket key
     * @param message   New message to persist
     * @return Ordered list of messages (including the new one), pruned to fit
     *         budget
     */
    public List<Map<String, Object>> appendAndBuild(String ticketKey, ConversationMessage message) {
        repository.save(message);
        return buildMessages(ticketKey);
    }

    /**
     * Selects messages within budget, always keeping the most recent N.
     * Older messages are included only if they fit within the remaining budget.
     */
    List<ConversationMessage> selectWithinBudget(List<ConversationMessage> allMessages) {
        int total = allMessages.size();

        // Always keep the most recent N messages
        int recentStart = Math.max(0, total - recentMessagesToKeep);
        List<ConversationMessage> recentMessages = allMessages.subList(recentStart, total);

        int recentTokens = recentMessages.stream()
                .mapToInt(ConversationMessage::getTokenCount)
                .sum();

        // If recent messages alone exceed or meet budget, return only recent
        if (recentTokens >= tokenBudget) {
            log.info("Recent {} messages use {} tokens (budget={}). No room for older messages.",
                    recentMessages.size(), recentTokens, tokenBudget);
            return new ArrayList<>(recentMessages);
        }

        // Try to include older messages, working from newest-old to oldest
        int remainingBudget = tokenBudget - recentTokens;
        List<ConversationMessage> olderMessages = allMessages.subList(0, recentStart);

        List<ConversationMessage> includedOlder = new ArrayList<>();
        // Iterate from the newest older message backward
        for (int i = olderMessages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = olderMessages.get(i);
            if (msg.getTokenCount() <= remainingBudget) {
                includedOlder.add(0, msg); // prepend to maintain order
                remainingBudget -= msg.getTokenCount();
            } else {
                break; // Stop when we can't fit the next oldest message
            }
        }

        // Combine: older (that fit) + recent
        List<ConversationMessage> result = new ArrayList<>(includedOlder);
        result.addAll(recentMessages);

        if (includedOlder.size() < olderMessages.size()) {
            log.info("Pruned {} of {} older messages to fit budget. Keeping {} total.",
                    olderMessages.size() - includedOlder.size(), olderMessages.size(), result.size());
        }

        return result;
    }

    /**
     * Converts ConversationMessage list to Claude API message format.
     * Merges consecutive same-role messages to satisfy Claude's alternating role
     * requirement.
     */
    private List<Map<String, Object>> toApiFormat(List<ConversationMessage> messages) {
        List<Map<String, Object>> apiMessages = new ArrayList<>();

        for (ConversationMessage msg : messages) {
            Object content;
            try {
                content = objectMapper.readValue(
                        msg.getContentJson(),
                        new TypeReference<List<Map<String, Object>>>() {
                        });
            } catch (Exception e) {
                // Fallback: treat as plain text wrapped in a content block
                log.warn("Failed to parse content JSON for message {}: {}", msg.getSk(), e.getMessage());
                content = msg.getContentJson();
            }

            // Merge with previous message if same role (Claude requires alternating roles)
            if (!apiMessages.isEmpty()) {
                Map<String, Object> lastMsg = apiMessages.get(apiMessages.size() - 1);
                if (msg.getRole().equals(lastMsg.get("role"))) {
                    log.info("Merging consecutive {} messages (sk={})", msg.getRole(), msg.getSk());
                    Object mergedContent = mergeContent(lastMsg.get("content"), content);
                    // Replace the last message with merged content (Map.of is immutable, so
                    // rebuild)
                    apiMessages.set(apiMessages.size() - 1, Map.of(
                            "role", msg.getRole(),
                            "content", mergedContent));
                    continue;
                }
            }

            if (content == null) {
                log.warn("Skipping message {} with null content", msg.getSk());
                continue;
            }

            apiMessages.add(Map.of(
                    "role", msg.getRole(),
                    "content", content));
        }

        log.info("Built {} API messages from {} persisted messages", apiMessages.size(), messages.size());
        return apiMessages;
    }

    /**
     * Merges two content values into one list.
     * Handles both List and String content types.
     */
    @SuppressWarnings("unchecked")
    private Object mergeContent(Object existing, Object additional) {
        List<Object> merged = new ArrayList<>();

        if (existing instanceof List) {
            merged.addAll((List<Object>) existing);
        } else if (existing instanceof String) {
            merged.add(Map.of("type", "text", "text", existing));
        }

        if (additional instanceof List) {
            merged.addAll((List<Object>) additional);
        } else if (additional instanceof String) {
            merged.add(Map.of("type", "text", "text", additional));
        }

        return merged;
    }
}
