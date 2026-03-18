package com.aidriven.mcp;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.spi.model.OperationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SpringAiMcpToolCallback}.
 */
@ExtendWith(MockitoExtension.class)
class SpringAiMcpToolCallbackTest {

    @Mock
    private McpBridgeToolProvider mcpProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Tool sampleTool;
    private SpringAiMcpToolCallback callback;

    @BeforeEach
    void setUp() {
        sampleTool = new Tool(
                "monitoring_search_logs",
                "Search application logs",
                Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string")),
                        "required", new String[]{"query"}
                )
        );
        callback = new SpringAiMcpToolCallback(mcpProvider, sampleTool, objectMapper);
    }

    @Nested
    class Construction {

        @Test
        void constructor_should_reject_null_provider() {
            assertThrows(NullPointerException.class,
                    () -> new SpringAiMcpToolCallback(null, sampleTool, objectMapper));
        }

        @Test
        void constructor_should_reject_null_tool() {
            assertThrows(NullPointerException.class,
                    () -> new SpringAiMcpToolCallback(mcpProvider, null, objectMapper));
        }

        @Test
        void constructor_should_reject_null_objectMapper() {
            assertThrows(NullPointerException.class,
                    () -> new SpringAiMcpToolCallback(mcpProvider, sampleTool, null));
        }
    }

    @Nested
    class ToolDefinitionMapping {

        @Test
        void getToolDefinition_should_return_correct_name() {
            ToolDefinition def = callback.getToolDefinition();
            assertEquals("monitoring_search_logs", def.name());
        }

        @Test
        void getToolDefinition_should_return_correct_description() {
            ToolDefinition def = callback.getToolDefinition();
            assertEquals("Search application logs", def.description());
        }

        @Test
        void getToolDefinition_should_serialize_input_schema_as_json() {
            ToolDefinition def = callback.getToolDefinition();
            String schema = def.inputSchema();
            assertNotNull(schema);
            assertTrue(schema.contains("\"type\""));
            assertTrue(schema.contains("\"object\""));
            assertTrue(schema.contains("\"query\""));
        }

        @Test
        void getToolDefinition_should_handle_empty_schema() {
            Tool emptyTchemaTool = new Tool("test_tool", "Test", Map.of());
            SpringAiMcpToolCallback cb = new SpringAiMcpToolCallback(mcpProvider, emptySchemaTool(), objectMapper);

            ToolDefinition def = cb.getToolDefinition();
            assertNotNull(def.inputSchema());
        }

        private Tool emptySchemaTool() {
            return new Tool("test_tool", "Test", Map.of("type", "object", "properties", Map.of()));
        }
    }

    @Nested
    class ExecutionWithStringInput {

        @Test
        void call_should_delegate_to_mcpProvider_and_return_content() {
            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.success("call-1", "Found 5 entries"));

            String result = callback.call("{\"query\": \"error\"}");

            assertEquals("Found 5 entries", result);
            verify(mcpProvider).execute(any(OperationContext.class), any(ToolCall.class));
        }

        @Test
        void call_should_pass_parsed_json_input_to_provider() {
            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.success("call-1", "ok"));

            callback.call("{\"query\": \"error 500\", \"limit\": 10}");

            ArgumentCaptor<ToolCall> captor = ArgumentCaptor.forClass(ToolCall.class);
            verify(mcpProvider).execute(any(OperationContext.class), captor.capture());

            ToolCall captured = captor.getValue();
            assertEquals("monitoring_search_logs", captured.name());
            assertNotNull(captured.input());
            assertEquals("error 500", captured.input().get("query").asText());
            assertEquals(10, captured.input().get("limit").asInt());
        }

        @Test
        void call_should_handle_null_input() {
            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.success("call-1", "ok"));

            String result = callback.call(null);

            assertEquals("ok", result);
        }

        @Test
        void call_should_handle_empty_input() {
            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.success("call-1", "ok"));

            String result = callback.call("");

            assertEquals("ok", result);
        }

        @Test
        void call_should_handle_invalid_json_input() {
            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.success("call-1", "ok"));

            String result = callback.call("not-valid-json");

            assertEquals("ok", result);
            // Should still delegate, wrapping as raw value
            verify(mcpProvider).execute(any(OperationContext.class), any(ToolCall.class));
        }

        @Test
        void call_should_return_error_content_when_tool_fails() {
            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.error("call-1", "Rate limit exceeded"));

            String result = callback.call("{\"query\": \"test\"}");

            assertEquals("Rate limit exceeded", result);
        }

        @Test
        void call_should_return_error_message_when_provider_throws() {
            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenThrow(new RuntimeException("Connection lost"));

            String result = callback.call("{\"query\": \"test\"}");

            assertTrue(result.contains("Connection lost"));
            assertTrue(result.contains("Error executing tool"));
        }
    }

    @Nested
    class ExecutionWithToolContext {

        @Test
        void call_should_extract_operationContext_from_toolContext() {
            OperationContext opCtx = OperationContext.builder()
                    .tenantId("my-tenant")
                    .requestId("req-123")
                    .build();
            ToolContext toolContext = new ToolContext(Map.of("operationContext", opCtx));

            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.success("call-1", "ok"));

            callback.call("{}", toolContext);

            ArgumentCaptor<OperationContext> ctxCaptor = ArgumentCaptor.forClass(OperationContext.class);
            verify(mcpProvider).execute(ctxCaptor.capture(), any(ToolCall.class));

            assertEquals("my-tenant", ctxCaptor.getValue().tenantId());
        }

        @Test
        void call_should_use_default_context_when_toolContext_is_null() {
            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.success("call-1", "ok"));

            callback.call("{}", null);

            ArgumentCaptor<OperationContext> ctxCaptor = ArgumentCaptor.forClass(OperationContext.class);
            verify(mcpProvider).execute(ctxCaptor.capture(), any(ToolCall.class));

            assertEquals("spring-ai-adapter", ctxCaptor.getValue().tenantId());
        }

        @Test
        void call_should_use_default_context_when_operationContext_key_missing() {
            ToolContext toolContext = new ToolContext(Map.of("otherKey", "value"));

            when(mcpProvider.execute(any(OperationContext.class), any(ToolCall.class)))
                    .thenReturn(ToolResult.success("call-1", "ok"));

            callback.call("{}", toolContext);

            ArgumentCaptor<OperationContext> ctxCaptor = ArgumentCaptor.forClass(OperationContext.class);
            verify(mcpProvider).execute(ctxCaptor.capture(), any(ToolCall.class));

            assertEquals("spring-ai-adapter", ctxCaptor.getValue().tenantId());
        }
    }

    @Nested
    class Accessors {

        @Test
        void getToolName_should_return_prefixed_name() {
            assertEquals("monitoring_search_logs", callback.getToolName());
        }

        @Test
        void getMcpProvider_should_return_underlying_provider() {
            assertSame(mcpProvider, callback.getMcpProvider());
        }
    }

    @Nested
    class CallIdGeneration {

        @Test
        void generateCallId_should_start_with_springai_prefix() {
            String callId = SpringAiMcpToolCallback.generateCallId();
            assertTrue(callId.startsWith("springai-"));
        }

        @Test
        void generateCallId_should_produce_unique_ids() {
            String id1 = SpringAiMcpToolCallback.generateCallId();
            String id2 = SpringAiMcpToolCallback.generateCallId();
            assertNotEquals(id1, id2);
        }

        @Test
        void generateCallId_should_match_toolUseId_pattern() {
            String callId = SpringAiMcpToolCallback.generateCallId();
            // ToolResult validates: ^[a-zA-Z0-9_-]+$
            assertTrue(callId.matches("^[a-zA-Z0-9_-]+$"),
                    "Generated call ID must match ToolResult's required pattern: " + callId);
        }
    }
}
