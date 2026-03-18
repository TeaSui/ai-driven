package com.aidriven.core.agent;

import com.aidriven.core.agent.model.ConversationMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bridges our DynamoDB-backed {@link ConversationRepository} to Spring AI's
 * {@link ChatMemoryRepository} interface.
 *
 * <p>This adapter enables a gradual migration path:
 * <ol>
 *   <li><b>Phase 1 (current):</b> Wraps existing DynamoDB persistence so Spring AI's
 *       {@code MessageWindowChatMemory} can use it as its backing store.</li>
 *   <li><b>Phase 2 (future):</b> Once the orchestrator is fully migrated to Spring AI's
 *       ChatClient/Advisor pattern, this adapter can be replaced with a direct DynamoDB
 *       implementation of {@code ChatMemoryRepository} or a Spring AI-provided store.</li>
 * </ol>
 *
 * <h3>Conversation ID mapping</h3>
 * <p>Spring AI uses a single string {@code conversationId}. We map this to our composite
 * key {@code tenantId + "#" + ticketKey} using the format {@code "tenantId#ticketKey"}.
 * This matches the DynamoDB partition key format (minus the "CONV#" prefix).
 *
 * <h3>Message type mapping</h3>
 * <ul>
 *   <li>{@code ConversationMessage(role="user")} maps to {@link UserMessage}</li>
 *   <li>{@code ConversationMessage(role="assistant")} maps to {@link AssistantMessage}</li>
 *   <li>{@code ConversationMessage(author="tool-output")} maps to {@link ToolResponseMessage}</li>
 * </ul>
 */
@Slf4j
public class DynamoChatMemoryRepository implements ChatMemoryRepository {

    private static final String CONVERSATION_ID_SEPARATOR = "#";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> CONTENT_BLOCKS_TYPE =
            new TypeReference<>() {};

    private final ConversationRepository repository;

    public DynamoChatMemoryRepository(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * Not supported -- our DynamoDB schema does not efficiently support listing
     * all conversation IDs across all tenants.
     *
     * @return empty list (listing all conversations is not supported)
     */
    @Override
    public List<String> findConversationIds() {
        log.warn("findConversationIds() is not supported by DynamoDB single-table design. "
                + "DynamoDB requires a scan operation which is expensive at scale.");
        return Collections.emptyList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        String[] parts = parseConversationId(conversationId);
        List<ConversationMessage> messages = repository.getConversation(parts[0], parts[1]);
        return toSpringAiMessages(messages);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(messages, "messages must not be null");

        String[] parts = parseConversationId(conversationId);
        String tenantId = parts[0];
        String ticketKey = parts[1];

        // Clear existing conversation before saving the full list.
        // Spring AI's MessageWindowChatMemory always passes the complete pruned list.
        repository.deleteConversation(tenantId, ticketKey);

        for (int index = 0; index < messages.size(); index++) {
            Message springMessage = messages.get(index);
            ConversationMessage dynamoMessage = fromSpringAiMessage(
                    springMessage, tenantId, ticketKey, index);
            repository.save(dynamoMessage);
        }

        log.debug("Saved {} messages for conversationId={}", messages.size(), conversationId);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        String[] parts = parseConversationId(conversationId);
        repository.deleteConversation(parts[0], parts[1]);
    }

    // --- Conversion: ConversationMessage -> Spring AI Message ---

    List<Message> toSpringAiMessages(List<ConversationMessage> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (ConversationMessage msg : messages) {
            Message springMessage = toSpringAiMessage(msg);
            if (springMessage != null) {
                result.add(springMessage);
            }
        }
        return result;
    }

    private Message toSpringAiMessage(ConversationMessage msg) {
        String textContent = extractTextContent(msg.getContentJson());

        if ("tool-output".equals(msg.getAuthor())) {
            return buildToolResponseMessage(msg.getContentJson());
        }

        return switch (msg.getRole()) {
            case "user" -> new UserMessage(textContent);
            case "assistant" -> buildAssistantMessage(msg.getContentJson(), textContent);
            default -> {
                log.warn("Unknown message role '{}', treating as user message", msg.getRole());
                yield new UserMessage(textContent);
            }
        };
    }

    private AssistantMessage buildAssistantMessage(String contentJson, String textContent) {
        List<AssistantMessage.ToolCall> toolCalls = extractToolCalls(contentJson);
        if (!toolCalls.isEmpty()) {
            return AssistantMessage.builder()
                    .content(textContent)
                    .toolCalls(toolCalls)
                    .build();
        }
        return new AssistantMessage(textContent);
    }

    private ToolResponseMessage buildToolResponseMessage(String contentJson) {
        List<ToolResponse> responses = extractToolResponses(contentJson);
        return ToolResponseMessage.builder()
                .responses(responses)
                .build();
    }

    // --- Conversion: Spring AI Message -> ConversationMessage ---

    ConversationMessage fromSpringAiMessage(
            Message springMessage, String tenantId, String ticketKey, int sequence) {

        String role = mapMessageTypeToRole(springMessage.getMessageType());
        String author = resolveAuthor(springMessage);
        String contentJson = toContentJson(springMessage);
        int tokenCount = estimateTokens(contentJson);

        Instant now = Instant.now();
        return ConversationMessage.builder()
                .pk(ConversationMessage.createPk(tenantId, ticketKey))
                .sk(ConversationMessage.createSk(now, sequence))
                .role(role)
                .author(author)
                .timestamp(now)
                .ttl(ConversationMessage.defaultTtl())
                .contentJson(contentJson)
                .tokenCount(tokenCount)
                .build();
    }

    // --- Helpers ---

    /**
     * Parses a conversationId into [tenantId, ticketKey].
     * Expected format: "tenantId#ticketKey" (e.g., "acme#ONC-100").
     */
    static String[] parseConversationId(String conversationId) {
        int separatorIndex = conversationId.indexOf(CONVERSATION_ID_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex >= conversationId.length() - 1) {
            throw new IllegalArgumentException(
                    "Invalid conversationId format. Expected 'tenantId#ticketKey', got: " + conversationId);
        }
        return new String[]{
                conversationId.substring(0, separatorIndex),
                conversationId.substring(separatorIndex + 1)
        };
    }

    /**
     * Creates a conversationId from tenantId and ticketKey.
     */
    public static String toConversationId(String tenantId, String ticketKey) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(ticketKey, "ticketKey must not be null");
        return tenantId + CONVERSATION_ID_SEPARATOR + ticketKey;
    }

    private static String mapMessageTypeToRole(MessageType type) {
        return switch (type) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "user";
            case SYSTEM -> "system";
        };
    }

    private static String resolveAuthor(Message message) {
        return switch (message.getMessageType()) {
            case USER -> "unknown-user";
            case ASSISTANT -> "ai-agent";
            case TOOL -> "tool-output";
            case SYSTEM -> "system";
        };
    }

    private String toContentJson(Message message) {
        try {
            if (message instanceof ToolResponseMessage toolMsg) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                for (ToolResponse response : toolMsg.getResponses()) {
                    blocks.add(Map.of(
                            "type", "tool_result",
                            "tool_use_id", response.id(),
                            "content", response.responseData()));
                }
                return OBJECT_MAPPER.writeValueAsString(blocks);
            }

            String text = message.getText();
            if (text == null) {
                text = "";
            }
            List<Map<String, String>> blocks = List.of(Map.of("type", "text", "text", text));
            return OBJECT_MAPPER.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message to JSON", e);
            return "[]";
        }
    }

    /**
     * Extracts plain text from Claude-format content JSON.
     * Concatenates all text blocks, ignoring tool_use and tool_result blocks.
     */
    static String extractTextContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            List<Map<String, Object>> blocks = OBJECT_MAPPER.readValue(contentJson, CONTENT_BLOCKS_TYPE);
            StringBuilder textBuilder = new StringBuilder();
            for (Map<String, Object> block : blocks) {
                String type = String.valueOf(block.getOrDefault("type", ""));
                if ("text".equals(type)) {
                    Object text = block.get("text");
                    if (text != null) {
                        if (!textBuilder.isEmpty()) {
                            textBuilder.append("\n");
                        }
                        textBuilder.append(text);
                    }
                }
            }
            return textBuilder.toString();
        } catch (JsonProcessingException e) {
            // Fallback: return raw content if it's not valid JSON array
            return contentJson;
        }
    }

    /**
     * Extracts tool calls from assistant message content JSON.
     */
    private List<AssistantMessage.ToolCall> extractToolCalls(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> blocks = OBJECT_MAPPER.readValue(contentJson, CONTENT_BLOCKS_TYPE);
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            for (Map<String, Object> block : blocks) {
                if ("tool_use".equals(block.get("type"))) {
                    String id = String.valueOf(block.get("id"));
                    String name = String.valueOf(block.get("name"));
                    Object input = block.get("input");
                    String arguments = input != null ? OBJECT_MAPPER.writeValueAsString(input) : "{}";
                    toolCalls.add(new AssistantMessage.ToolCall(id, "function", name, arguments));
                }
            }
            return toolCalls;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * Extracts tool responses from tool-output message content JSON.
     */
    private List<ToolResponse> extractToolResponses(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> blocks = OBJECT_MAPPER.readValue(contentJson, CONTENT_BLOCKS_TYPE);
            List<ToolResponse> responses = new ArrayList<>();
            for (Map<String, Object> block : blocks) {
                if ("tool_result".equals(block.get("type"))) {
                    String toolUseId = String.valueOf(block.get("tool_use_id"));
                    Object content = block.get("content");
                    String data = content != null ? content.toString() : "";
                    responses.add(new ToolResponse(toolUseId, "tool_result", data));
                }
            }
            if (responses.isEmpty()) {
                // Fallback: wrap the entire content as a single tool response
                responses.add(new ToolResponse("unknown", "tool_result", contentJson));
            }
            return responses;
        } catch (JsonProcessingException e) {
            return List.of(new ToolResponse("unknown", "tool_result", contentJson));
        }
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
