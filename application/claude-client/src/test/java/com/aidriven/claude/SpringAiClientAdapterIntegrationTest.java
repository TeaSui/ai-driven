package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.agent.AiClient.ToolUseResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SpringAiClientAdapter} that verify end-to-end
 * interaction with the real Anthropic API.
 *
 * <p>These tests are excluded from the default {@code ./gradlew test} run.
 * To execute them, run:
 * <pre>
 *   ANTHROPIC_API_KEY=sk-... ./gradlew :claude-client:integrationTest
 * </pre>
 *
 * <p>Tests are ordered to run simple chat first, then tool-use scenarios,
 * ensuring basic connectivity is validated before more complex flows.
 */
@Tag("integration")
@TestMethodOrder(OrderAnnotation.class)
class SpringAiClientAdapterIntegrationTest {

    private static final String INTEGRATION_MODEL = "claude-haiku-4-5-20251001";
    private static final int MAX_TOKENS = 256;
    private static final double TEMPERATURE = 0.0;

    private static AiClient client;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "ANTHROPIC_API_KEY environment variable is not set; skipping integration tests");

        client = new SpringAiClientAdapter(apiKey, INTEGRATION_MODEL, MAX_TOKENS, TEMPERATURE);
    }

    @Test
    @Order(1)
    void should_return_correct_answer_for_simple_chat() throws Exception {
        String response = client.chat(
                "You are a helpful assistant.",
                "What is 2+2? Reply with just the number.");

        assertThat(response)
                .isNotNull()
                .isNotBlank()
                .contains("4");
    }

    @Test
    @Order(2)
    void should_trigger_tool_use_when_tool_is_available() throws Exception {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "What's the weather in Tokyo?"));

        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string")),
                "required", List.of("city"));

        List<Map<String, Object>> tools = List.of(
                Map.of("name", "get_weather",
                        "description", "Get weather for a city",
                        "input_schema", inputSchema));

        ToolUseResponse response = client.chatWithTools(
                "You are a helpful weather assistant. Use the get_weather tool to answer weather questions.",
                messages,
                tools);

        assertThat(response.hasToolUse()).isTrue();
        assertThat(response.stopReason()).isEqualTo("tool_use");

        ArrayNode contentBlocks = response.contentBlocks();
        assertThat(contentBlocks).isNotEmpty();

        boolean foundToolUseBlock = false;
        for (JsonNode block : contentBlocks) {
            if ("tool_use".equals(block.path("type").asText())) {
                foundToolUseBlock = true;
                assertThat(block.path("name").asText()).isEqualTo("get_weather");
                assertThat(block.path("id").asText()).isNotBlank();

                String inputText = block.path("input").toString().toLowerCase();
                assertThat(inputText).contains("tokyo");
            }
        }
        assertThat(foundToolUseBlock)
                .as("Expected at least one tool_use content block")
                .isTrue();
    }

    @Test
    @Order(3)
    void should_complete_tool_result_round_trip() throws Exception {
        // Step 1: Initial user message triggering tool use
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string")),
                "required", List.of("city"));

        List<Map<String, Object>> tools = List.of(
                Map.of("name", "get_weather",
                        "description", "Get weather for a city",
                        "input_schema", inputSchema));

        String systemPrompt = "You are a helpful weather assistant. Use the get_weather tool to answer weather questions.";

        List<Map<String, Object>> initialMessages = List.of(
                Map.of("role", "user", "content", "What's the weather in Tokyo?"));

        ToolUseResponse toolUseResponse = client.chatWithTools(systemPrompt, initialMessages, tools);
        assertThat(toolUseResponse.hasToolUse()).isTrue();

        // Extract tool_use id from the response
        String toolUseId = null;
        List<Map<String, Object>> assistantContentBlocks = new ArrayList<>();

        for (JsonNode block : toolUseResponse.contentBlocks()) {
            String blockType = block.path("type").asText();
            if ("tool_use".equals(blockType)) {
                toolUseId = block.path("id").asText();
            }
            // Reconstruct content blocks as Maps for the next request
            Map<String, Object> blockMap = new HashMap<>();
            blockMap.put("type", blockType);
            if ("text".equals(blockType)) {
                blockMap.put("text", block.path("text").asText());
            } else if ("tool_use".equals(blockType)) {
                blockMap.put("id", block.path("id").asText());
                blockMap.put("name", block.path("name").asText());
                // Convert JsonNode input to a Map
                Map<String, Object> inputMap = new HashMap<>();
                block.path("input").fields().forEachRemaining(
                        entry -> inputMap.put(entry.getKey(), entry.getValue().asText()));
                blockMap.put("input", inputMap);
            }
            assistantContentBlocks.add(blockMap);
        }

        assertThat(toolUseId)
                .as("Expected a tool_use_id in the response")
                .isNotNull();

        // Step 2: Send tool result back to the model
        List<Map<String, Object>> toolResultContent = List.of(
                Map.of("type", "tool_result",
                        "tool_use_id", toolUseId,
                        "content", "Sunny, 22 degrees Celsius, light breeze"));

        List<Map<String, Object>> roundTripMessages = List.of(
                Map.of("role", "user", "content", "What's the weather in Tokyo?"),
                Map.of("role", "assistant", "content", assistantContentBlocks),
                Map.of("role", "user", "content", toolResultContent));

        ToolUseResponse finalResponse = client.chatWithTools(systemPrompt, roundTripMessages, tools);

        assertThat(finalResponse.stopReason()).isEqualTo("end_turn");
        assertThat(finalResponse.getText())
                .isNotBlank()
                .as("Final response should contain weather information from the tool result");
    }

    @Test
    @Order(4)
    void should_report_token_usage_for_simple_chat() throws Exception {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "Say hello."));

        ToolUseResponse response = client.chatWithTools(
                "You are a helpful assistant.",
                messages,
                List.of());

        assertThat(response.inputTokens())
                .as("Input tokens should be greater than zero")
                .isGreaterThan(0);
        assertThat(response.outputTokens())
                .as("Output tokens should be greater than zero")
                .isGreaterThan(0);
    }

    // Note: Retry behavior is difficult to test against the real API without
    // forcing transient failures. Retry logic is verified in unit tests via mocks.
    // To confirm retry behavior in integration, enable DEBUG logging for
    // org.springframework.retry and observe retry attempts in the logs when
    // the API returns 429 or 5xx responses.
}
