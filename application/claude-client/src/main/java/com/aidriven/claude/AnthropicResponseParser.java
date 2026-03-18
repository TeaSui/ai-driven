package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.spi.provider.AiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared Anthropic message-format parser used by both {@link SpringAiClientAdapter}
 * and {@link BedrockClient}.
 *
 * <p>
 * Centralises all JSON response parsing and continuation-prompt generation so
 * both clients stay in sync. Stateless and thread-safe.
 */
@Slf4j
@RequiredArgsConstructor
class AnthropicResponseParser {

    private final ObjectMapper objectMapper;

    // ─── Tool-use parsing ────────────────────────────────────────────────────

    /**
     * Parses a raw Anthropic JSON response into a {@link AiClient.ToolUseResponse}.
     * Works for both Claude API and Bedrock (same JSON schema).
     */
    AiClient.ToolUseResponse parseToolUseResponse(JsonNode responseJson) {
        String stopReason = responseJson.has("stop_reason")
                ? responseJson.get("stop_reason").asText()
                : "unknown";

        ArrayNode contentBlocks = (ArrayNode) responseJson.get("content");
        if (contentBlocks == null) {
            contentBlocks = objectMapper.createArrayNode();
        }

        int inputTokens = 0, outputTokens = 0;
        JsonNode usage = responseJson.get("usage");
        if (usage != null) {
            inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
            outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
        }

        return new AiClient.ToolUseResponse(contentBlocks, stopReason, inputTokens, outputTokens);
    }

    // ─── Chat-response parsing ───────────────────────────────────────────────

    /** Extracts text content from a raw API response JsonNode. */
    String extractTextContent(JsonNode responseJson) {
        JsonNode content = responseJson.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new RuntimeException("Unexpected response format: missing 'content' array");
        }

        StringBuilder result = new StringBuilder();
        for (JsonNode block : content) {
            JsonNode typeNode = block.get("type");
            if (typeNode != null && "text".equals(typeNode.asText())) {
                result.append(block.get("text").asText());
            }
        }
        return result.toString();
    }

    /**
     * Parses stop_reason and logs token usage from a raw Anthropic JSON response.
     */
    ParsedChatResponse parseChatResponse(JsonNode responseJson) {
        String stopReason = responseJson.has("stop_reason")
                ? responseJson.get("stop_reason").asText()
                : "unknown";

        // Log token usage
        JsonNode usage = responseJson.get("usage");
        if (usage != null) {
            log.info("Token usage: input={}, output={}",
                    usage.has("input_tokens") ? usage.get("input_tokens").asInt() : "?",
                    usage.has("output_tokens") ? usage.get("output_tokens").asInt() : "?");
        }

        String text = extractTextContent(responseJson);
        return new ParsedChatResponse(text, stopReason);
    }

    /**
     * Builds the AiProvider.ChatResponse adapter for a tool-use response.
     * Shared by SpringAiClientAdapter and BedrockClient.
     */
    AiProvider.ChatResponse toChatResponse(AiClient.ToolUseResponse response) {
        return new AnthropicChatResponse(response, objectMapper);
    }

    // ─── Inner types ─────────────────────────────────────────────────────────

    record ParsedChatResponse(String text, String stopReason) {
    }

    @RequiredArgsConstructor
    static class AnthropicChatResponse implements AiProvider.ChatResponse {
        private final AiClient.ToolUseResponse response;
        private final ObjectMapper objectMapper;

        @Override
        public String getText() {
            return response.getText();
        }

        @Override
        public List<Map<String, Object>> getToolCalls() {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (JsonNode block : response.contentBlocks()) {
                if ("tool_use".equals(block.path("type").asText())) {
                    calls.add(objectMapper.convertValue(block, Map.class));
                }
            }
            return calls;
        }

        @Override
        public int getInputTokens() {
            return response.inputTokens();
        }

        @Override
        public int getOutputTokens() {
            return response.outputTokens();
        }
    }
}
