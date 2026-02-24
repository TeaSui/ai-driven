package com.aidriven.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;

/**
 * Platform-agnostic interface for AI model interactions with tool-use support.
 * The core module depends on this interface; implementations (e.g.,
 * ClaudeClient)
 * live in their own modules.
 *
 * <p>
 * This follows the same pattern as SourceControlClient/IssueTrackerClient:
 * core defines interfaces, concrete implementations live in separate modules.
 * </p>
 */
public interface AiClient {

    /**
     * Sends a message to the AI model with tool definitions.
     * Returns the raw response including tool_use content blocks.
     *
     * @param systemPrompt System prompt
     * @param messages     Conversation messages (supports text, tool_use,
     *                     tool_result blocks)
     * @param tools        Tool definitions in model API format
     * @return Response with content blocks and stop reason
     */
    ToolUseResponse chatWithTools(String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) throws Exception;

    /**
     * Sends a simple chat message to the AI model.
     * This is a convenience method for non-tool-use scenarios.
     *
     * @param systemPrompt System prompt
     * @param userMessage User message content
     * @return The AI model's response text
     */
    String chat(String systemPrompt, String userMessage) throws Exception;

    /**
     * Gets the model identifier currently in use.
     *
     * @return The model identifier (e.g., "claude-opus-4-6")
     */
    String getModel();

    /**
     * Returns a new instance of this client with the specified model.
     * Implementations should return a new instance with the updated model
     * configuration.
     *
     * @param model The model identifier to use
     * @return A new client instance configured with the specified model
     */
    AiClient withModel(String model);

    /**
     * Returns a new instance of this client with the specified max tokens.
     * Implementations should return a new instance with the updated configuration.
     *
     * @param maxTokens Maximum tokens for model responses
     * @return A new client instance configured with the specified max tokens
     */
    AiClient withMaxTokens(int maxTokens);

    /**
     * Returns a new instance of this client with the specified temperature.
     * Implementations should return a new instance with the updated configuration.
     *
     * @param temperature Temperature for sampling (0.0 to 1.0)
     * @return A new client instance configured with the specified temperature
     */
    AiClient withTemperature(double temperature);

    /**
     * Response from the AI model when tools are available.
     * Contains raw content blocks (text + tool_use) and stop reason.
     */
    record ToolUseResponse(
            ArrayNode contentBlocks,
            String stopReason,
            int inputTokens,
            int outputTokens) {

        /** Returns true if the model wants to use a tool. */
        public boolean hasToolUse() {
            return "tool_use".equals(stopReason);
        }

        /** Extracts text content from all text blocks. */
        public String getText() {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentBlocks) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            return sb.toString();
        }

        /** Total tokens consumed. */
        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
