package com.aidriven.mcp;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.spi.model.OperationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SpringAiMcpClientAdapter}.
 */
@ExtendWith(MockitoExtension.class)
class SpringAiMcpClientAdapterTest {

    @Mock
    private McpSyncClient mcpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========================================================================
    // Helpers
    // ========================================================================

    private McpBridgeToolProvider createProviderWithTools(McpSchema.Tool... tools) {
        McpSchema.ListToolsResult result = new McpSchema.ListToolsResult(List.of(tools), null);
        when(mcpClient.listTools()).thenReturn(result);
        return new McpBridgeToolProvider("monitoring", mcpClient);
    }

    private McpSchema.Tool mcpTool(String name, String description) {
        return new McpSchema.Tool(name, description,
                new McpSchema.JsonSchema("object",
                        Map.of("query", Map.<String, Object>of("type", "string")),
                        List.of("query"), null, null, null));
    }

    // ========================================================================
    // Construction
    // ========================================================================

    @Nested
    class Construction {

        @Test
        void constructor_should_reject_null_provider() {
            assertThrows(NullPointerException.class,
                    () -> new SpringAiMcpClientAdapter(null, objectMapper));
        }

        @Test
        void constructor_should_reject_null_objectMapper() {
            McpBridgeToolProvider provider = createProviderWithTools(mcpTool("test", "Test"));
            assertThrows(NullPointerException.class,
                    () -> new SpringAiMcpClientAdapter(provider, null));
        }

        @Test
        void from_should_create_adapter_with_default_objectMapper() {
            McpBridgeToolProvider provider = createProviderWithTools(mcpTool("test", "Test"));
            SpringAiMcpClientAdapter adapter = SpringAiMcpClientAdapter.from(provider);

            assertNotNull(adapter);
            assertEquals("monitoring", adapter.getNamespace());
        }

        @Test
        void from_should_create_adapter_with_custom_objectMapper() {
            McpBridgeToolProvider provider = createProviderWithTools(mcpTool("test", "Test"));
            SpringAiMcpClientAdapter adapter = SpringAiMcpClientAdapter.from(provider, objectMapper);

            assertNotNull(adapter);
            assertEquals(1, adapter.getToolCount());
        }
    }

    // ========================================================================
    // ToolCallbackProvider contract
    // ========================================================================

    @Nested
    class ToolCallbackProviderContract {

        @Test
        void getToolCallbacks_should_return_one_callback_per_tool() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"),
                    mcpTool("get_metric", "Get metric"),
                    mcpTool("list_alerts", "List alerts"));

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] callbacks = adapter.getToolCallbacks();

            assertEquals(3, callbacks.length);
        }

        @Test
        void getToolCallbacks_should_preserve_tool_names_with_namespace() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"));

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] callbacks = adapter.getToolCallbacks();

            assertEquals("monitoring_search_logs", callbacks[0].getToolDefinition().name());
        }

        @Test
        void getToolCallbacks_should_preserve_descriptions() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search application logs"));

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] callbacks = adapter.getToolCallbacks();

            assertEquals("Search application logs", callbacks[0].getToolDefinition().description());
        }

        @Test
        void getToolCallbacks_should_include_input_schema_as_json() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"));

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] callbacks = adapter.getToolCallbacks();

            String schema = callbacks[0].getToolDefinition().inputSchema();
            assertNotNull(schema);
            assertTrue(schema.contains("query"));
            assertTrue(schema.contains("object"));
        }

        @Test
        void getToolCallbacks_should_return_empty_array_when_no_tools() {
            when(mcpClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
            McpBridgeToolProvider provider = new McpBridgeToolProvider("empty", mcpClient);

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] callbacks = adapter.getToolCallbacks();

            assertEquals(0, callbacks.length);
        }

        @Test
        void getToolCallbacks_should_return_same_cached_array() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"));

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] first = adapter.getToolCallbacks();
            ToolCallback[] second = adapter.getToolCallbacks();

            assertSame(first, second);
        }
    }

    // ========================================================================
    // End-to-end: ToolCallback.call delegates to MCP
    // ========================================================================

    @Nested
    class EndToEndExecution {

        @Test
        void toolCallback_call_should_delegate_to_mcpProvider() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Found 3 entries")), false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] callbacks = adapter.getToolCallbacks();

            String result = callbacks[0].call("{\"query\": \"error\"}");

            assertEquals("Found 3 entries", result);
            verify(mcpClient).callTool(any(McpSchema.CallToolRequest.class));
        }

        @Test
        void toolCallback_call_should_handle_mcp_errors() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Rate limit exceeded")), true);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] callbacks = adapter.getToolCallbacks();

            String result = callbacks[0].call("{\"query\": \"test\"}");

            assertEquals("Rate limit exceeded", result);
        }

        @Test
        void toolCallback_call_should_handle_provider_exception() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"));

            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class)))
                    .thenThrow(new RuntimeException("Connection lost"));

            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);
            ToolCallback[] callbacks = adapter.getToolCallbacks();

            String result = callbacks[0].call("{\"query\": \"test\"}");

            // McpBridgeToolProvider catches the exception internally and returns
            // ToolResult.error with "MCP tool error: <message>"
            assertTrue(result.contains("Connection lost"),
                    "Expected result to contain 'Connection lost' but was: " + result);
        }
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    @Nested
    class Accessors {

        @Test
        void getNamespace_should_return_provider_namespace() {
            McpBridgeToolProvider provider = createProviderWithTools(mcpTool("test", "Test"));
            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);

            assertEquals("monitoring", adapter.getNamespace());
        }

        @Test
        void getToolCount_should_match_callbacks_length() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("a", "A"), mcpTool("b", "B"));
            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);

            assertEquals(2, adapter.getToolCount());
        }

        @Test
        void getMcpProvider_should_return_underlying_provider() {
            McpBridgeToolProvider provider = createProviderWithTools(mcpTool("test", "Test"));
            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);

            assertSame(provider, adapter.getMcpProvider());
        }

        @Test
        void toString_should_contain_namespace_and_tool_count() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("a", "A"), mcpTool("b", "B"));
            SpringAiMcpClientAdapter adapter = new SpringAiMcpClientAdapter(provider, objectMapper);

            String str = adapter.toString();
            assertTrue(str.contains("monitoring"));
            assertTrue(str.contains("2"));
        }
    }
}
