package com.aidriven.app.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * Service that wraps Spring AI's {@link ChatClient} fluent API for agent interactions.
 *
 * <p>Provides both streaming and blocking call modes:
 * <ul>
 *   <li>{@link #streamResponse} returns a reactive {@link Flux} of content chunks,
 *       enabling real-time streaming to clients (e.g., SSE endpoints).</li>
 *   <li>{@link #callWithTools} performs a blocking call with explicit tool callbacks,
 *       returning the complete response text.</li>
 * </ul>
 *
 * <p>The ChatClient is pre-configured (via {@link SpringAiConfig}) with memory
 * advisors and the {@link AgentAdvisor} for cross-cutting concerns. This service
 * adds conversation-level and tool-level control on top of those defaults.
 */
@Slf4j
@Service
public class StreamingAgentService {

    private final ChatClient chatClient;

    public StreamingAgentService(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
    }

    /**
     * Streams a response from the AI model as individual content chunks.
     *
     * <p>The conversation memory advisor (configured on the ChatClient) automatically
     * persists the conversation under the given {@code conversationId}. The ID format
     * should match the DynamoDB key convention: {@code "tenantId#ticketKey"}.
     *
     * @param systemPrompt   the system prompt providing agent persona and context
     * @param userMessage    the user message to process
     * @param conversationId the conversation identifier for memory persistence
     * @return a Flux emitting content text chunks as they arrive from the model
     */
    public Flux<String> streamResponse(String systemPrompt, String userMessage, String conversationId) {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        Objects.requireNonNull(conversationId, "conversationId must not be null");

        log.info("Streaming response: conversationId={}, userMessageLength={}",
                conversationId, userMessage.length());

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(advisor -> advisor.param("chat_memory_conversation_id", conversationId))
                .stream()
                .content();
    }

    /**
     * Performs a blocking AI call with explicit tool callbacks.
     *
     * <p>Use this method when the agent needs to execute tool calls (e.g., MCP tools,
     * source control operations) as part of its response. The ChatClient's built-in
     * tool calling manager handles the tool execution loop automatically.
     *
     * @param systemPrompt the system prompt providing agent persona and context
     * @param userMessage  the user message to process
     * @param tools        list of tool callbacks available for the model to invoke
     * @return the complete text response from the model (after all tool calls resolve)
     */
    public String callWithTools(String systemPrompt, String userMessage, List<ToolCallback> tools) {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        Objects.requireNonNull(tools, "tools must not be null");

        log.info("Calling with {} tools, userMessageLength={}", tools.size(), userMessage.length());

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .toolCallbacks(tools.toArray(new ToolCallback[0]))
                .call()
                .content();
    }
}
