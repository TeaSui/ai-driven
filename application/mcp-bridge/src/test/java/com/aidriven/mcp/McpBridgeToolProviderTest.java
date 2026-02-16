package com.aidriven.mcp;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link McpBridgeToolProvider}.
 *
 * <p>
 * Tests cover: tool discovery, namespace prefixing, name sanitization/reverse
 * mapping,
 * execute happy path, error handling, schema conversion, and edge cases.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class McpBridgeToolProviderTest {

    @Mock
    private McpSyncClient mcpClient;

    private final ObjectMapper mapper = new ObjectMapper();

    // ========================================================================
    // Helper: build a provider with standard mock tools
    // ========================================================================

    private McpBridgeToolProvider createProviderWithTools(McpSchema.Tool... tools) {
        McpSchema.ListToolsResult result = new McpSchema.ListToolsResult(
                List.of(tools), null);
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
    // Constructor & Initialization
    // ========================================================================

    @Nested
    class Initialization {

        @Test
        void constructor_nullNamespace_throwsNPE() {
            assertThrows(NullPointerException.class,
                    () -> new McpBridgeToolProvider(null, mcpClient));
        }

        @Test
        void constructor_nullClient_throwsNPE() {
            assertThrows(NullPointerException.class,
                    () -> new McpBridgeToolProvider("test", null));
        }

        @Test
        void constructor_callsListToolsOnCreation() {
            when(mcpClient.listTools()).thenReturn(
                    new McpSchema.ListToolsResult(List.of(), null));
            new McpBridgeToolProvider("test", mcpClient);
            verify(mcpClient).listTools();
        }
    }

    // ========================================================================
    // Tool Discovery
    // ========================================================================

    @Nested
    class ToolDiscovery {

        @Test
        void namespace_returnsConfiguredNamespace() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"));
            assertEquals("monitoring", provider.namespace());
        }

        @Test
        void toolDefinitions_discoversAndPrefixesTools() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search application logs"));

            List<Tool> tools = provider.toolDefinitions();

            assertEquals(1, tools.size());
            assertEquals("monitoring_search_logs", tools.get(0).name());
            assertEquals("Search application logs", tools.get(0).description());
            assertNotNull(tools.get(0).inputSchema());
        }

        @Test
        void toolDefinitions_multipleTools_allPrefixed() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search logs"),
                    mcpTool("get_metric", "Get metric data"),
                    mcpTool("list_alerts", "List active alerts"));

            List<Tool> tools = provider.toolDefinitions();
            assertEquals(3, tools.size());
            assertTrue(tools.stream().allMatch(t -> t.name().startsWith("monitoring_")));
        }

        @Test
        void toolDefinitions_emptyWhenServerReturnsNullTools() {
            when(mcpClient.listTools()).thenReturn(
                    new McpSchema.ListToolsResult(null, null));
            McpBridgeToolProvider provider = new McpBridgeToolProvider("empty", mcpClient);
            assertTrue(provider.toolDefinitions().isEmpty());
        }

        @Test
        void toolDefinitions_emptyWhenServerReturnsNullResult() {
            when(mcpClient.listTools()).thenReturn(null);
            McpBridgeToolProvider provider = new McpBridgeToolProvider("empty", mcpClient);
            assertTrue(provider.toolDefinitions().isEmpty());
        }

        @Test
        void toolDefinitions_emptyWhenServerThrows() {
            when(mcpClient.listTools()).thenThrow(new RuntimeException("Server unreachable"));
            McpBridgeToolProvider provider = new McpBridgeToolProvider("broken", mcpClient);
            assertTrue(provider.toolDefinitions().isEmpty());
        }

        @Test
        void toolDefinitions_nullDescription_usesDefault() {
            McpSchema.Tool toolNoDesc = new McpSchema.Tool("my_tool", null,
                    new McpSchema.JsonSchema("object", Map.<String, Object>of(), List.of(), null, null, null));
            McpBridgeToolProvider provider = createProviderWithTools(toolNoDesc);

            assertEquals("MCP tool: my_tool", provider.toolDefinitions().get(0).description());
        }

        @Test
        void toolDefinitions_nullSchema_usesEmptyObjectSchema() {
            McpSchema.Tool toolNoSchema = new McpSchema.Tool("my_tool", "desc", (McpSchema.JsonSchema) null);
            McpBridgeToolProvider provider = createProviderWithTools(toolNoSchema);

            Map<String, Object> schema = provider.toolDefinitions().get(0).inputSchema();
            assertEquals("object", schema.get("type"));
            assertNotNull(schema.get("properties"));
        }

        @Test
        void toolDefinitions_areCachedAfterFirstDiscovery() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            List<Tool> first = provider.toolDefinitions();
            List<Tool> second = provider.toolDefinitions();

            assertSame(first, second); // same list reference
            verify(mcpClient, times(1)).listTools(); // called once in constructor
        }

        @Test
        void toolDefinitions_schemaWithRequiredFields_preserved() {
            McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
                    Map.<String, Object>of("query", Map.of("type", "string"), "limit", Map.of("type", "integer")),
                    List.of("query"),
                    null, null, null);
            McpSchema.Tool tool = new McpSchema.Tool("search", "Search", schema);
            McpBridgeToolProvider provider = createProviderWithTools(tool);

            Map<String, Object> resultSchema = provider.toolDefinitions().get(0).inputSchema();
            assertEquals("object", resultSchema.get("type"));
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) resultSchema.get("required");
            assertTrue(required.contains("query"));
        }
    }

    // ========================================================================
    // Name Sanitization
    // ========================================================================

    @Nested
    class NameSanitization {

        @Test
        void sanitize_hyphensToUnderscores() {
            assertEquals("search_logs", McpBridgeToolProvider.sanitizeToolName("search-logs"));
        }

        @Test
        void sanitize_dotsToUnderscores() {
            assertEquals("get_data_v2", McpBridgeToolProvider.sanitizeToolName("get.data.v2"));
        }

        @Test
        void sanitize_collapseMultipleUnderscores() {
            assertEquals("list_items", McpBridgeToolProvider.sanitizeToolName("list__items"));
        }

        @Test
        void sanitize_trimLeadingTrailingUnderscores() {
            assertEquals("tool", McpBridgeToolProvider.sanitizeToolName("_tool_"));
        }

        @Test
        void sanitize_complexName() {
            assertEquals("datadog_search_logs_v2",
                    McpBridgeToolProvider.sanitizeToolName("datadog.search-logs--v2"));
        }

        @Test
        void sanitize_nullReturnsUnknown() {
            assertEquals("unknown", McpBridgeToolProvider.sanitizeToolName(null));
        }

        @Test
        void sanitize_alphanumericUnchanged() {
            assertEquals("searchLogs", McpBridgeToolProvider.sanitizeToolName("searchLogs"));
        }

        @Test
        void sanitize_specialCharsRemoved() {
            assertEquals("get_data", McpBridgeToolProvider.sanitizeToolName("get@data"));
        }
    }

    // ========================================================================
    // Tool Name Reverse Mapping (Bug Fix Verification)
    // ========================================================================

    @Nested
    class ToolNameReverseMapping {

        @Test
        void execute_usesOriginalMcpToolName_notSanitized() {
            // MCP server has "search-logs" (hyphen) — sanitized to "monitoring_search_logs"
            // callTool must receive "search-logs" (original), NOT "search_logs"
            McpSchema.Tool hyphenatedTool = new McpSchema.Tool(
                    "search-logs", "Search application logs",
                    new McpSchema.JsonSchema("object", Map.<String, Object>of(), List.of(), null, null, null));

            McpBridgeToolProvider provider = createProviderWithTools(hyphenatedTool);

            assertEquals("search-logs", provider.getOriginalToolName("monitoring_search_logs"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("result")), false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ToolCall call = new ToolCall("call-1", "monitoring_search_logs", mapper.createObjectNode());
            provider.execute(call);

            ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
            verify(mcpClient).callTool(captor.capture());

            assertEquals("search-logs", captor.getValue().name(),
                    "MCP callTool must use original hyphenated name, not sanitized");
        }

        @Test
        void execute_dotSeparatedToolName_usesOriginal() {
            McpSchema.Tool dottedTool = new McpSchema.Tool(
                    "datadog.search.metrics", "Search Datadog metrics",
                    new McpSchema.JsonSchema("object", Map.<String, Object>of(), List.of(), null, null, null));

            McpBridgeToolProvider provider = createProviderWithTools(dottedTool);

            assertEquals("datadog.search.metrics",
                    provider.getOriginalToolName("monitoring_datadog_search_metrics"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ok")), false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ToolCall call = new ToolCall("c-2", "monitoring_datadog_search_metrics", mapper.createObjectNode());
            provider.execute(call);

            ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
            verify(mcpClient).callTool(captor.capture());
            assertEquals("datadog.search.metrics", captor.getValue().name());
        }

        @Test
        void execute_alreadySanitizedName_usesOriginalMapping() {
            McpSchema.Tool cleanTool = new McpSchema.Tool(
                    "search_logs", "Search logs",
                    new McpSchema.JsonSchema("object", Map.<String, Object>of(), List.of(), null, null, null));

            McpBridgeToolProvider provider = createProviderWithTools(cleanTool);
            assertEquals("search_logs", provider.getOriginalToolName("monitoring_search_logs"));
        }

        @Test
        void getOriginalToolName_unknownName_returnsNull() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));
            assertNull(provider.getOriginalToolName("unknown_tool_name"));
        }
    }

    // ========================================================================
    // Execute Happy Path
    // ========================================================================

    @Nested
    class ExecuteHappyPath {

        @Test
        void execute_successfulCall_returnsContent() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Found 5 log entries")), false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ObjectNode input = mapper.createObjectNode();
            input.put("query", "error");
            ToolCall call = new ToolCall("call-1", "monitoring_search_logs", input);

            ToolResult result = provider.execute(call);

            assertFalse(result.isError());
            assertEquals("Found 5 log entries", result.content());
            assertEquals("call-1", result.toolUseId());
        }

        @Test
        void execute_multipleTextContents_concatenatedWithNewline() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(
                            new McpSchema.TextContent("Line 1"),
                            new McpSchema.TextContent("Line 2"),
                            new McpSchema.TextContent("Line 3")),
                    false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ToolCall call = new ToolCall("call-2", "monitoring_search_logs", mapper.createObjectNode());
            ToolResult result = provider.execute(call);

            assertEquals("Line 1\nLine 2\nLine 3", result.content());
        }

        @Test
        void execute_passesInputArgumentsToMcp() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ok")), false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ObjectNode input = mapper.createObjectNode();
            input.put("query", "error 500");
            input.put("limit", 10);
            ToolCall call = new ToolCall("call-3", "monitoring_search_logs", input);
            provider.execute(call);

            ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
            verify(mcpClient).callTool(captor.capture());

            Map<String, Object> args = captor.getValue().arguments();
            assertEquals("error 500", args.get("query"));
            assertEquals(10, args.get("limit"));
        }

        @Test
        void execute_nullInput_sendsEmptyMap() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ok")), false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ToolCall call = new ToolCall("call-4", "monitoring_search_logs", null);
            provider.execute(call);

            ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
            verify(mcpClient).callTool(captor.capture());
            assertTrue(captor.getValue().arguments().isEmpty());
        }
    }

    // ========================================================================
    // Execute Error Handling
    // ========================================================================

    @Nested
    class ExecuteErrorHandling {

        @Test
        void execute_mcpReturnsError_markedAsError() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Rate limit exceeded")), true);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ToolCall call = new ToolCall("call-e1", "monitoring_search_logs", mapper.createObjectNode());
            ToolResult result = provider.execute(call);

            assertTrue(result.isError());
            assertTrue(result.content().contains("Rate limit"));
        }

        @Test
        void execute_mcpThrowsException_returnsErrorResult() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class)))
                    .thenThrow(new RuntimeException("Connection lost"));

            ToolCall call = new ToolCall("call-e2", "monitoring_search_logs", mapper.createObjectNode());
            ToolResult result = provider.execute(call);

            assertTrue(result.isError());
            assertTrue(result.content().contains("Connection lost"));
        }

        @Test
        void execute_mcpReturnsNull_emptySuccessContent() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(null);

            ToolCall call = new ToolCall("call-e3", "monitoring_search_logs", mapper.createObjectNode());
            ToolResult result = provider.execute(call);

            assertFalse(result.isError());
            assertEquals("", result.content());
        }

        @Test
        void execute_mcpReturnsEmptyContent_emptyString() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult(List.of(), false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ToolCall call = new ToolCall("call-e4", "monitoring_search_logs", mapper.createObjectNode());
            ToolResult result = provider.execute(call);

            assertFalse(result.isError());
            assertEquals("", result.content());
        }

        @Test
        void execute_mcpReturnsNullContent_emptyString() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));

            McpSchema.CallToolResult mcpResult = new McpSchema.CallToolResult((java.util.List<McpSchema.Content>) null,
                    false);
            when(mcpClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(mcpResult);

            ToolCall call = new ToolCall("call-e5", "monitoring_search_logs", mapper.createObjectNode());
            ToolResult result = provider.execute(call);

            assertFalse(result.isError());
            assertEquals("", result.content());
        }
    }

    // ========================================================================
    // Max Output & Misc
    // ========================================================================

    @Nested
    class Miscellaneous {

        @Test
        void maxOutputChars_returnsDefault30k() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));
            assertEquals(30_000, provider.maxOutputChars());
        }

        @Test
        void getMcpClient_returnsUnderlyingClient() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"));
            assertSame(mcpClient, provider.getMcpClient());
        }

        @Test
        void toString_containsNamespaceAndToolCount() {
            McpBridgeToolProvider provider = createProviderWithTools(
                    mcpTool("search_logs", "Search"),
                    mcpTool("get_metric", "Get metric"));

            String str = provider.toString();
            assertTrue(str.contains("monitoring"));
            assertTrue(str.contains("2"));
        }
    }
}
