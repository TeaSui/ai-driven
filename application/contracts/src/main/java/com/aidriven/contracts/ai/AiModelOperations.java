package com.aidriven.contracts.ai;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;

/**
 * Platform-agnostic contract for AI model interactions with tool-use support.
 * <p>
 * This interface lives in the contracts module so that custom AI providers
 * (OpenAI, Bedrock, Gemini, local models, etc.) can implement it
 * without depending on core.
 * </p>
 *
 * <p>Existing implementations: ClaudeClient.</p>
 */
public interface AiModelOperations {

    /**
     * Sends a message to the AI model with tool definitions.
     *
     * @param systemPrompt System prompt
     * @param messages     Conversation messages
     * @param tools        Tool definitions in model API format
     * @return Response with content blocks and stop reason
     */
    AiToolResponse chatWithTools(String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) throws Exception;

    /**
     * Response from the AI model when tools are available.
     */
    record AiToolResponse(
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
            if (contentBlocks != null) {
                for (var block : contentBlocks) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
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
