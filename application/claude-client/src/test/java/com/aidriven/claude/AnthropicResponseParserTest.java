package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.spi.provider.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for {@link AnthropicResponseParser} verifying that the shared
 * Anthropic message-format parsing works correctly for both Claude and Bedrock.
 */
class AnthropicResponseParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AnthropicResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new AnthropicResponseParser(objectMapper);
    }

    // ─── parseToolUseResponse ────────────────────────────────────────────────

    @Test
    void parse_tool_use_response_extracts_stop_reason() throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("stop_reason", "tool_use");
        json.putArray("content");

        AiClient.ToolUseResponse result = parser.parseToolUseResponse(json);

        assertThat(result.stopReason()).isEqualTo("tool_use");
        assertThat(result.hasToolUse()).isTrue();
    }

    @Test
    void parse_tool_use_response_extracts_token_counts() throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("stop_reason", "end_turn");
        json.putArray("content");
        ObjectNode usage = json.putObject("usage");
        usage.put("input_tokens", 150);
        usage.put("output_tokens", 42);

        AiClient.ToolUseResponse result = parser.parseToolUseResponse(json);

        assertThat(result.inputTokens()).isEqualTo(150);
        assertThat(result.outputTokens()).isEqualTo(42);
        assertThat(result.totalTokens()).isEqualTo(192);
    }

    @Test
    void parse_tool_use_response_handles_missing_usage() throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("stop_reason", "end_turn");
        json.putArray("content");
        // No "usage" field

        AiClient.ToolUseResponse result = parser.parseToolUseResponse(json);

        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }

    @Test
    void parse_tool_use_response_handles_missing_content() throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("stop_reason", "end_turn");
        // No "content" → should return empty ArrayNode, not null

        AiClient.ToolUseResponse result = parser.parseToolUseResponse(json);

        assertThat(result.contentBlocks()).isNotNull();
        assertThat(result.contentBlocks().size()).isZero();
    }

    // ─── extractTextContent ──────────────────────────────────────────────────

    @Test
    void extract_text_content_returns_all_text_blocks_concatenated() throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        ArrayNode content = json.putArray("content");
        ObjectNode block1 = content.addObject();
        block1.put("type", "text");
        block1.put("text", "Hello, ");
        ObjectNode block2 = content.addObject();
        block2.put("type", "text");
        block2.put("text", "world!");

        String text = parser.extractTextContent(json);

        assertThat(text).isEqualTo("Hello, world!");
    }

    @Test
    void extract_text_content_skips_non_text_blocks() throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        ArrayNode content = json.putArray("content");
        ObjectNode toolUseBlock = content.addObject();
        toolUseBlock.put("type", "tool_use");
        toolUseBlock.put("id", "toolu_01");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", "Done");

        String text = parser.extractTextContent(json);

        assertThat(text).isEqualTo("Done");
    }

    @Test
    void extract_text_content_throws_on_missing_content() {
        ObjectNode json = objectMapper.createObjectNode();
        // No "content" field

        assertThatThrownBy(() -> parser.extractTextContent(json))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("missing 'content' array");
    }

    // ─── continuationPrompt ──────────────────────────────────────────────────

    @Test
    void continuation_prompt_for_json_text() {
        String prompt = parser.continuationPrompt("{\"key\": \"some partial json");
        assertThat(prompt).isEqualTo(AnthropicResponseParser.JSON_CONTINUATION_PROMPT);
    }

    @Test
    void continuation_prompt_for_plain_text() {
        String prompt = parser.continuationPrompt("Here is my analysis of the code");
        assertThat(prompt).isEqualTo(AnthropicResponseParser.TEXT_CONTINUATION_PROMPT);
    }

    // ─── toChatResponse ──────────────────────────────────────────────────────

    @Test
    void to_chat_response_returns_text_and_tokens() throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("stop_reason", "end_turn");
        ArrayNode content = json.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", "Result text");
        ObjectNode usage = json.putObject("usage");
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 5);

        AiClient.ToolUseResponse response = parser.parseToolUseResponse(json);
        AiProvider.ChatResponse chatResponse = parser.toChatResponse(response);

        assertThat(chatResponse.getText()).isEqualTo("Result text");
        assertThat(chatResponse.getInputTokens()).isEqualTo(10);
        assertThat(chatResponse.getOutputTokens()).isEqualTo(5);
    }

    @Test
    void to_chat_response_extracts_tool_calls() throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("stop_reason", "tool_use");
        ArrayNode content = json.putArray("content");
        ObjectNode toolBlock = content.addObject();
        toolBlock.put("type", "tool_use");
        toolBlock.put("id", "toolu_01");
        toolBlock.put("name", "source_control_get_file");
        json.putArray("usage"); // dummy usage

        AiClient.ToolUseResponse response = parser.parseToolUseResponse(json);
        AiProvider.ChatResponse chatResponse = parser.toChatResponse(response);

        assertThat(chatResponse.getToolCalls()).hasSize(1);
        assertThat(chatResponse.getToolCalls().get(0).get("name"))
                .isEqualTo("source_control_get_file");
    }
}
