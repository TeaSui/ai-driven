package com.aidriven.mcp;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.spi.model.OperationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bridges ANY MCP server into our {@link ToolProvider} contract.
 */
@Slf4j
public class McpBridgeToolProvider implements ToolProvider {

    private static final int DEFAULT_MAX_OUTPUT_CHARS = 30_000;

    private final String namespace;
    private final McpSyncClient mcpClient;
    private final List<Tool> cachedTools;
    private final ObjectMapper objectMapper;
    private final Map<String, String> toolNameMapping;

    public McpBridgeToolProvider(String namespace, McpSyncClient mcpClient) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
        this.objectMapper = new ObjectMapper();
        this.toolNameMapping = new HashMap<>();
        this.cachedTools = discoverTools();
        log.info("McpBridgeToolProvider '{}' initialized with {} tools", namespace, cachedTools.size());
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public List<Tool> toolDefinitions() {
        return cachedTools;
    }

    @Override
    public ToolResult execute(OperationContext context, ToolCall call) {
        String mcpToolName = toolNameMapping.getOrDefault(call.name(), stripNamespacePrefix(call.name()));
        log.info("MCP bridge execute: {} → mcp:{} (namespace={})", call.name(), mcpToolName, namespace);

        try {
            Map<String, Object> arguments = convertInputToMap(call.input());
            McpSchema.CallToolResult mcpResult = mcpClient.callTool(
                    new McpSchema.CallToolRequest(mcpToolName, arguments));

            return convertResult(call.id(), mcpResult);
        } catch (Exception e) {
            log.error("MCP tool execution failed: {} - {}", call.name(), e.getMessage(), e);
            return ToolResult.error(call.id(), "MCP tool error: " + e.getMessage());
        }
    }

    @Override
    public int maxOutputChars() {
        return DEFAULT_MAX_OUTPUT_CHARS;
    }

    private List<Tool> discoverTools() {
        try {
            McpSchema.ListToolsResult toolsResult = mcpClient.listTools();
            if (toolsResult == null || toolsResult.tools() == null) {
                log.warn("MCP server '{}' returned no tools", namespace);
                return List.of();
            }

            return toolsResult.tools().stream()
                    .map(this::convertMcpTool)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to discover tools from MCP server '{}': {}", namespace, e.getMessage(), e);
            return List.of();
        }
    }

    private Tool convertMcpTool(McpSchema.Tool mcpTool) {
        try {
            String prefixedName = namespace + "_" + sanitizeToolName(mcpTool.name());
            String description = mcpTool.description() != null ? mcpTool.description() : "MCP tool: " + mcpTool.name();
            Map<String, Object> inputSchema = convertInputSchema(mcpTool.inputSchema());
            toolNameMapping.put(prefixedName, mcpTool.name());
            return new Tool(prefixedName, description, inputSchema);
        } catch (Exception e) {
            log.warn("Failed to convert MCP tool '{}': {}", mcpTool.name(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> convertInputSchema(McpSchema.JsonSchema mcpSchema) {
        if (mcpSchema == null) {
            return Map.of("type", "object", "properties", Map.of());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", mcpSchema.properties() != null ? mcpSchema.properties() : Map.of());
        if (mcpSchema.required() != null)
            schema.put("required", mcpSchema.required());
        if (mcpSchema.additionalProperties() != null)
            schema.put("additionalProperties", mcpSchema.additionalProperties());
        return schema;
    }

    private ToolResult convertResult(String toolUseId, McpSchema.CallToolResult mcpResult) {
        if (mcpResult == null)
            return ToolResult.success(toolUseId, "");
        boolean isError = Boolean.TRUE.equals(mcpResult.isError());
        String content = extractTextContent(mcpResult);
        return isError ? ToolResult.error(toolUseId, content) : ToolResult.success(toolUseId, content);
    }

    private String extractTextContent(McpSchema.CallToolResult mcpResult) {
        if (mcpResult.content() == null || mcpResult.content().isEmpty())
            return "";
        return mcpResult.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    static String sanitizeToolName(String mcpName) {
        if (mcpName == null)
            return "unknown";
        return mcpName.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private String stripNamespacePrefix(String toolName) {
        String prefix = namespace + "_";
        return toolName.startsWith(prefix) ? toolName.substring(prefix.length()) : toolName;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertInputToMap(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode())
            return Map.of();
        try {
            return objectMapper.convertValue(input, Map.class);
        } catch (Exception e) {
            log.warn("Failed to convert tool input to map: {}", e.getMessage());
            return Map.of();
        }
    }

    public McpSyncClient getMcpClient() {
        return mcpClient;
    }

    public String getOriginalToolName(String prefixedName) {
        return toolNameMapping.get(prefixedName);
    }

    @Override
    public String toString() {
        return "McpBridgeToolProvider{namespace='" + namespace + "', tools="
                + (cachedTools != null ? cachedTools.size() : 0) + "}";
    }
}
