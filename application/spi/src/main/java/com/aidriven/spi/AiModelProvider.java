package com.aidriven.spi;

import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface for AI model integrations.
 * Implementations can wrap Claude, OpenAI, Bedrock, or any other LLM.
 *
 * <p>Each tenant may use a different AI provider based on their subscription
 * and compliance requirements.</p>
 */
public interface AiModelProvider {

    /**
     * Unique identifier for this provider (e.g., "claude", "openai", "bedrock").
     */
    String providerId();

    /**
     * Human-readable display name.
     */
    String displayName();

    /**
     * Sends a chat message and returns the response text.
     *
     * @param systemPrompt System-level instructions
     * @param userMessage  User message content
     * @param options      Provider-specific options (model, temperature, etc.)
     * @return The model's response text
     */
    String chat(String systemPrompt, String userMessage, Map<String, Object> options) throws Exception;

    /**
     * Sends a message with tool definitions for function calling.
     *
     * @param systemPrompt System prompt
     * @param messages     Conversation messages
     * @param tools        Tool definitions
     * @param options      Provider-specific options
     * @return Response containing content blocks and stop reason
     */
    ToolUseResponse chatWithTools(String systemPrompt,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools,
                                   Map<String, Object> options) throws Exception;

    /**
     * Returns the list of supported model identifiers.
     */
    List<String> supportedModels();

    /**
     * Response from tool-use capable chat.
     */
    record ToolUseResponse(
            Object contentBlocks,
            String stopReason,
            int inputTokens,
            int outputTokens) {

        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
