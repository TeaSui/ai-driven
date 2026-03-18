package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter that bridges Spring AI's {@link ChatMemory} to the same API contract as
 * {@link ConversationWindowManager}, enabling incremental migration from the custom
 * conversation management to Spring AI's built-in memory abstractions.
 *
 * <h3>Migration strategy</h3>
 * <ol>
 *   <li><b>Phase 1 (current):</b> The adapter wraps Spring AI's {@code ChatMemory}
 *       (backed by {@link DynamoChatMemoryRepository}) and exposes the same
 *       {@code appendAndBuild / buildMessages} contract that
 *       {@link ConversationWindowManager} provides. The {@link AgentOrchestrator} can
 *       use either implementation.</li>
 *   <li><b>Phase 2:</b> Migrate {@code AgentOrchestrator} to use Spring AI's
 *       ChatClient + MessageChatMemoryAdvisor directly, removing the need for the
 *       custom {@code appendAndBuild} pattern.</li>
 *   <li><b>Phase 3:</b> Remove {@code ConversationWindowManager},
 *       {@code SpringAiChatMemoryAdapter}, and use Spring AI natively.</li>
 * </ol>
 *
 * <h3>Window strategy differences</h3>
 * <p>Spring AI's {@link MessageWindowChatMemory} uses a <b>message-count</b> based
 * window (e.g., keep last 20 messages). Our {@link ConversationWindowManager} uses a
 * <b>token-budget</b> based window with guaranteed recent message retention.
 * This adapter uses message-count windowing via Spring AI. For token-budget windowing,
 * continue using {@code ConversationWindowManager} until Spring AI adds token-aware
 * memory (planned for future releases).</p>
 *
 * <h3>Output format</h3>
 * <p>The {@code buildMessages} and {@code appendAndBuild} methods return messages in
 * the same Claude API format ({@code List<Map<String, Object>>} with role/content keys)
 * that the {@link AgentOrchestrator} expects, making this a drop-in replacement.</p>
 */
@Slf4j
public class SpringAiChatMemoryAdapter {

    private final ChatMemory chatMemory;
    private final DynamoChatMemoryRepository dynamoRepository;

    /**
     * Creates an adapter using the provided ChatMemory implementation.
     *
     * @param chatMemory       Spring AI ChatMemory (typically MessageWindowChatMemory)
     * @param dynamoRepository the DynamoDB-backed repository for message conversion
     */
    public SpringAiChatMemoryAdapter(ChatMemory chatMemory, DynamoChatMemoryRepository dynamoRepository) {
        this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory must not be null");
        this.dynamoRepository = Objects.requireNonNull(dynamoRepository, "dynamoRepository must not be null");
    }

    /**
     * Factory method to create a fully wired adapter using DynamoDB persistence.
     *
     * @param conversationRepository existing DynamoDB conversation repository
     * @param maxMessages            maximum number of messages to retain in the window
     * @return configured adapter ready for use
     */
    public static SpringAiChatMemoryAdapter create(
            ConversationRepository conversationRepository, int maxMessages) {

        DynamoChatMemoryRepository dynamoRepo = new DynamoChatMemoryRepository(conversationRepository);
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(dynamoRepo)
                .maxMessages(maxMessages)
                .build();
        return new SpringAiChatMemoryAdapter(memory, dynamoRepo);
    }

    /**
     * Builds the message list for the Claude API call, applying the Spring AI
     * memory window strategy.
     *
     * <p>Matches the contract of {@link ConversationWindowManager#buildMessages}.
     *
     * @param tenantId  the tenant ID
     * @param ticketKey Jira ticket key
     * @return ordered list of messages in Claude API format
     */
    public List<Map<String, Object>> buildMessages(String tenantId, String ticketKey) {
        String conversationId = DynamoChatMemoryRepository.toConversationId(tenantId, ticketKey);
        List<Message> springMessages = chatMemory.get(conversationId);
        return toApiFormat(springMessages);
    }

    /**
     * Saves a new message and builds the pruned message list in one operation.
     *
     * <p>Matches the contract of {@link ConversationWindowManager#appendAndBuild}.
     *
     * @param tenantId  the tenant ID
     * @param ticketKey Jira ticket key
     * @param message   new message to persist (our ConversationMessage format)
     * @return ordered list of messages (including the new one), pruned by window
     */
    public List<Map<String, Object>> appendAndBuild(
            String tenantId, String ticketKey, ConversationMessage message) {

        String conversationId = DynamoChatMemoryRepository.toConversationId(tenantId, ticketKey);

        // Convert our message to Spring AI message format
        Message springMessage = convertToSpringMessage(message);

        // Add to Spring AI memory (which handles windowing and persistence)
        chatMemory.add(conversationId, springMessage);

        // Retrieve the windowed conversation
        List<Message> windowedMessages = chatMemory.get(conversationId);

        log.debug("appendAndBuild: conversationId={}, totalAfterWindow={}",
                conversationId, windowedMessages.size());

        return toApiFormat(windowedMessages);
    }

    /**
     * Clears the conversation history for a ticket.
     *
     * @param tenantId  the tenant ID
     * @param ticketKey Jira ticket key
     */
    public void clearConversation(String tenantId, String ticketKey) {
        String conversationId = DynamoChatMemoryRepository.toConversationId(tenantId, ticketKey);
        chatMemory.clear(conversationId);
    }

    /**
     * Provides direct access to the underlying Spring AI ChatMemory for
     * advanced use cases (e.g., wiring into ChatClient advisors).
     */
    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    // --- Conversion helpers ---

    /**
     * Converts a ConversationMessage to the corresponding Spring AI Message type.
     */
    Message convertToSpringMessage(ConversationMessage message) {
        String textContent = DynamoChatMemoryRepository.extractTextContent(message.getContentJson());

        if ("tool-output".equals(message.getAuthor())) {
            // Tool results are added as user messages in Spring AI
            return new UserMessage("[Tool Output] " + textContent);
        }

        return switch (message.getRole()) {
            case "assistant" -> new AssistantMessage(textContent);
            default -> new UserMessage(textContent);
        };
    }

    /**
     * Converts Spring AI messages to Claude API format (List of role/content maps).
     * This matches the format expected by {@link AgentOrchestrator}.
     */
    List<Map<String, Object>> toApiFormat(List<Message> springMessages) {
        if (springMessages == null || springMessages.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> apiMessages = new ArrayList<>();

        for (Message msg : springMessages) {
            String role = mapToClaudeRole(msg);
            String text = msg.getText() != null ? msg.getText() : "";

            // Merge consecutive same-role messages (Claude requires alternating roles)
            if (!apiMessages.isEmpty()) {
                Map<String, Object> lastMsg = apiMessages.get(apiMessages.size() - 1);
                if (role.equals(lastMsg.get("role"))) {
                    log.debug("Merging consecutive {} messages", role);
                    Object existingContent = lastMsg.get("content");
                    Object mergedContent = mergeContent(existingContent, text);
                    apiMessages.set(apiMessages.size() - 1, Map.of(
                            "role", role,
                            "content", mergedContent));
                    continue;
                }
            }

            List<Map<String, String>> contentBlocks = List.of(Map.of("type", "text", "text", text));
            apiMessages.add(Map.of("role", role, "content", contentBlocks));
        }

        log.debug("Converted {} Spring AI messages to {} API messages",
                springMessages.size(), apiMessages.size());

        return apiMessages;
    }

    private String mapToClaudeRole(Message message) {
        return switch (message.getMessageType()) {
            case ASSISTANT -> "assistant";
            case USER, TOOL -> "user";
            case SYSTEM -> "user"; // System messages mapped to user for Claude API
        };
    }

    @SuppressWarnings("unchecked")
    private Object mergeContent(Object existing, String additionalText) {
        List<Object> merged = new ArrayList<>();

        if (existing instanceof List) {
            merged.addAll((List<Object>) existing);
        } else if (existing instanceof String existingStr) {
            merged.add(Map.of("type", "text", "text", existingStr));
        }

        merged.add(Map.of("type", "text", "text", additionalText));
        return merged;
    }
}
