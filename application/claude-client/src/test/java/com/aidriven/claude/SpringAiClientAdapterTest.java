package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicApi.AnthropicMessage;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionResponse;
import org.springframework.ai.anthropic.api.AnthropicApi.ContentBlock;
import org.springframework.ai.anthropic.api.AnthropicApi.ContentBlock.Type;
import org.springframework.ai.anthropic.api.AnthropicApi.Role;
import org.springframework.ai.anthropic.api.AnthropicApi.Tool;
import org.springframework.ai.anthropic.api.AnthropicApi.Usage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringAiClientAdapterTest {

    private static final String TEST_API_KEY = "sk-test-key-12345";
    private static final String TEST_MODEL = "claude-sonnet-4-6";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SpringAiClientAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SpringAiClientAdapter(TEST_API_KEY, TEST_MODEL, 4096, 0.2);
    }

    @Nested
    class FactoryMethods {

        @Test
        void should_create_from_secrets() {
            SecretsService secretsService = mock(SecretsService.class);
            when(secretsService.getSecret("test-arn")).thenReturn("sk-test-key");

            SpringAiClientAdapter client = SpringAiClientAdapter.fromSecrets(secretsService, "test-arn");

            assertThat(client).isNotNull();
            assertThat(client.getModel()).isEqualTo("claude-sonnet-4-6");
        }

        @Test
        void should_throw_when_secret_is_null() {
            SecretsService secretsService = mock(SecretsService.class);
            when(secretsService.getSecret("test-arn")).thenReturn(null);

            assertThatThrownBy(() -> SpringAiClientAdapter.fromSecrets(secretsService, "test-arn"))
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_throw_when_secret_is_blank() {
            SecretsService secretsService = mock(SecretsService.class);
            when(secretsService.getSecret("test-arn")).thenReturn("   ");

            assertThatThrownBy(() -> SpringAiClientAdapter.fromSecrets(secretsService, "test-arn"))
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class WithMethods {

        @Test
        void should_return_new_instance_with_different_model() {
            AiClient updated = adapter.withModel("claude-opus-4-6");

            assertThat(updated.getModel()).isEqualTo("claude-opus-4-6");
            assertThat(adapter.getModel()).isEqualTo(TEST_MODEL);
        }

        @Test
        void should_return_new_instance_with_different_max_tokens() {
            AiClient updated = adapter.withMaxTokens(8192);

            assertThat(updated).isNotSameAs(adapter);
            assertThat(updated.getModel()).isEqualTo(TEST_MODEL);
        }

        @Test
        void should_return_new_instance_with_different_temperature() {
            AiClient updated = adapter.withTemperature(0.8);

            assertThat(updated).isNotSameAs(adapter);
            assertThat(updated.getModel()).isEqualTo(TEST_MODEL);
        }
    }

    @Nested
    class ProviderName {

        @Test
        void should_return_spring_ai_claude() {
            assertThat(adapter.getName()).isEqualTo("spring-ai-claude");
        }
    }

    @Nested
    class MessageConversion {

        @Test
        void should_convert_simple_text_message() {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "Hello"));

            List<AnthropicMessage> result = adapter.convertMessages(messages);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).role()).isEqualTo(Role.USER);
            assertThat(result.get(0).content()).hasSize(1);
            assertThat(result.get(0).content().get(0).text()).isEqualTo("Hello");
        }

        @Test
        void should_convert_assistant_message() {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "assistant", "content", "Hi there"));

            List<AnthropicMessage> result = adapter.convertMessages(messages);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).role()).isEqualTo(Role.ASSISTANT);
        }

        @Test
        void should_convert_message_with_content_blocks() {
            List<Map<String, Object>> contentBlocks = List.of(
                    Map.of("type", "text", "text", "I will check"),
                    Map.of("type", "tool_use", "id", "tu_123", "name", "read_file",
                            "input", Map.of("path", "/src/main.java")));

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "assistant", "content", contentBlocks));

            List<AnthropicMessage> result = adapter.convertMessages(messages);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).content()).hasSize(2);

            ContentBlock textBlock = result.get(0).content().get(0);
            assertThat(textBlock.text()).isEqualTo("I will check");

            ContentBlock toolBlock = result.get(0).content().get(1);
            assertThat(toolBlock.id()).isEqualTo("tu_123");
            assertThat(toolBlock.name()).isEqualTo("read_file");
            assertThat(toolBlock.input()).containsEntry("path", "/src/main.java");
        }

        @Test
        void should_convert_tool_result_message() {
            List<Map<String, Object>> contentBlocks = List.of(
                    Map.of("type", "tool_result", "tool_use_id", "tu_123",
                            "content", "file contents here"));

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", contentBlocks));

            List<AnthropicMessage> result = adapter.convertMessages(messages);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).role()).isEqualTo(Role.USER);
            assertThat(result.get(0).content()).hasSize(1);
            assertThat(result.get(0).content().get(0).toolUseId()).isEqualTo("tu_123");
        }

        @Test
        void should_skip_blocks_without_type() {
            List<Map<String, Object>> contentBlocks = List.of(
                    Map.of("text", "no type here"),
                    Map.of("type", "text", "text", "has type"));

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", contentBlocks));

            List<AnthropicMessage> result = adapter.convertMessages(messages);

            assertThat(result.get(0).content()).hasSize(1);
            assertThat(result.get(0).content().get(0).text()).isEqualTo("has type");
        }

        @Test
        void should_handle_multiple_messages() {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "Question"),
                    Map.of("role", "assistant", "content", "Answer"),
                    Map.of("role", "user", "content", "Follow-up"));

            List<AnthropicMessage> result = adapter.convertMessages(messages);

            assertThat(result).hasSize(3);
            assertThat(result.get(0).role()).isEqualTo(Role.USER);
            assertThat(result.get(1).role()).isEqualTo(Role.ASSISTANT);
            assertThat(result.get(2).role()).isEqualTo(Role.USER);
        }
    }

    @Nested
    class ToolConversion {

        @Test
        void should_convert_tool_definitions() {
            List<Map<String, Object>> tools = List.of(
                    Map.of("name", "read_file",
                            "description", "Reads a file",
                            "input_schema", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "path", Map.of("type", "string")))));

            List<Tool> result = adapter.convertTools(tools);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("read_file");
            assertThat(result.get(0).description()).isEqualTo("Reads a file");
            assertThat(result.get(0).inputSchema()).containsKey("type");
        }

        @Test
        void should_return_empty_list_for_null_tools() {
            assertThat(adapter.convertTools(null)).isEmpty();
        }

        @Test
        void should_return_empty_list_for_empty_tools() {
            assertThat(adapter.convertTools(List.of())).isEmpty();
        }
    }

    @Nested
    class ResponseConversion {

        @Test
        void should_convert_text_response() {
            ChatCompletionResponse response = new ChatCompletionResponse(
                    "msg_123", "message", Role.ASSISTANT,
                    List.of(new ContentBlock("Hello world")),
                    TEST_MODEL, "end_turn", null,
                    new Usage(100, 50, null, null), null);

            AiClient.ToolUseResponse result = adapter.convertToToolUseResponse(response);

            assertThat(result.stopReason()).isEqualTo("end_turn");
            assertThat(result.inputTokens()).isEqualTo(100);
            assertThat(result.outputTokens()).isEqualTo(50);
            assertThat(result.hasToolUse()).isFalse();
            assertThat(result.getText()).isEqualTo("Hello world");
        }

        @Test
        void should_convert_tool_use_response() {
            ContentBlock toolUseBlock = new ContentBlock(
                    Type.TOOL_USE, null, null, null,
                    "tu_456", "write_file",
                    Map.of("path", "/tmp/test.txt", "content", "hello"),
                    null, null, null, null, null,
                    null, null, null, null, null, null);

            ChatCompletionResponse response = new ChatCompletionResponse(
                    "msg_456", "message", Role.ASSISTANT,
                    List.of(
                            new ContentBlock("I'll create the file"),
                            toolUseBlock),
                    TEST_MODEL, "tool_use", null,
                    new Usage(200, 100, null, null), null);

            AiClient.ToolUseResponse result = adapter.convertToToolUseResponse(response);

            assertThat(result.stopReason()).isEqualTo("tool_use");
            assertThat(result.hasToolUse()).isTrue();
            assertThat(result.inputTokens()).isEqualTo(200);
            assertThat(result.outputTokens()).isEqualTo(100);

            ArrayNode blocks = result.contentBlocks();
            assertThat(blocks).hasSize(2);
            assertThat(blocks.get(0).path("type").asText()).isEqualTo("text");
            assertThat(blocks.get(0).path("text").asText()).isEqualTo("I'll create the file");
            assertThat(blocks.get(1).path("type").asText()).isEqualTo("tool_use");
            assertThat(blocks.get(1).path("id").asText()).isEqualTo("tu_456");
            assertThat(blocks.get(1).path("name").asText()).isEqualTo("write_file");
            assertThat(blocks.get(1).path("input").path("path").asText()).isEqualTo("/tmp/test.txt");
        }

        @Test
        void should_handle_null_usage() {
            ChatCompletionResponse response = new ChatCompletionResponse(
                    "msg_789", "message", Role.ASSISTANT,
                    List.of(new ContentBlock("text")),
                    TEST_MODEL, "end_turn", null, null, null);

            AiClient.ToolUseResponse result = adapter.convertToToolUseResponse(response);

            assertThat(result.inputTokens()).isZero();
            assertThat(result.outputTokens()).isZero();
        }

        @Test
        void should_handle_null_content() {
            ChatCompletionResponse response = new ChatCompletionResponse(
                    "msg_null", "message", Role.ASSISTANT,
                    null, TEST_MODEL, "end_turn", null,
                    new Usage(10, 5, null, null), null);

            AiClient.ToolUseResponse result = adapter.convertToToolUseResponse(response);

            assertThat(result.contentBlocks()).isEmpty();
        }

        @Test
        void should_handle_null_stop_reason() {
            ChatCompletionResponse response = new ChatCompletionResponse(
                    "msg_ns", "message", Role.ASSISTANT,
                    List.of(new ContentBlock("text")),
                    TEST_MODEL, null, null,
                    new Usage(10, 5, null, null), null);

            AiClient.ToolUseResponse result = adapter.convertToToolUseResponse(response);

            assertThat(result.stopReason()).isEqualTo("unknown");
            assertThat(result.hasToolUse()).isFalse();
        }
    }
}
