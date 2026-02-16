package com.aidriven.mcp;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bridges ANY MCP server into our {@link ToolProvider} contract.
 *
 * <p>One instance per MCP server connection. Wraps the MCP Java SDK's
 * {@link McpSyncClient} and translates between MCP protocol and our
 * internal tool-use abstraction.</p>
 *
 * <h3>Mapping:</h3>
 * <ul>
 *   <li>MCP {@code tools/list} → our {@code toolDefinitions()}</li>
 *   <li>MCP {@code tools/call} → our {@code execute(ToolCall)}</li>
 *   <li>Tool names are prefixed with {@code namespace_} to avoid collisions</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * McpSyncClient mcpClient = McpConnectionFactory.connect(config);
 * ToolProvider provider = new McpBridgeToolProvider("monitoring", mcpClient);
 * toolRegistry.register(provider);
 * }</pre>
 */
@Slf4j
public class McpBridgeToolProvider implements ToolProvider {

    private static final int DEFAULT_MAX_OUTPUT_CHARS = 30_000;

    private final String namespace;
    private final McpSyncClient mcpClient;
    private final List<Tool> cachedTools;
    private final ObjectMapper objectMapper;
    /** Maps sanitized prefixed tool name → original MCP tool name for callTool dispatch. */
    private final Map<String, String> toolNameMapping;

    /**
     * Creates a bridge for an MCP server.
     *
     * @param namespace Namespace prefix for all tools (e.g., "monitoring")
     * @param mcpClient Connected MCP client (must be initialized)
     */
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
    public ToolResult execute(ToolCall call) {
        // Use mapping to get the ORIGINAL MCP tool name (pre-sanitization)
        // e.g., "monitoring_search_logs" → "search-logs" (original MCP name)
        String mcpToolName = toolNameMapping.getOrDefault(call.name(), stripNamespacePrefix(call.name()));
        log.info("MCP bridge execute: {} → mcp:{} (namespace={})", call.name(), mcpToolName, namespace);

        try {
            // Convert JsonNode input to Map for MCP SDK
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

    // --- Tool Discovery ---

    /**
     * Discovers tools from the MCP server via tools/list and converts them
     * to our internal Tool format with namespace-prefixed names.
     */
    private List<Tool> discoverTools() {
        try {
            McpSchema.ListToolsResult toolsResult = mcpClient.listTools();

            if (toolsResult == null || toolsResult.tools() == null) {
                log.warn("MCP server '{}' returned no tools", namespace);
                return List.of();
            }

            List<Tool> tools = toolsResult.tools().stream()
                    .map(this::convertMcpTool)
                    .filter(Objects::nonNull)
                    .toList();

            log.info("Discovered {} tools from MCP server '{}'", tools.size(), namespace);
            if (log.isDebugEnabled()) {
                tools.forEach(t -> log.debug("  Tool: {} — {}", t.name(), t.description()));
            }

            return tools;

        } catch (Exception e) {
            log.error("Failed to discover tools from MCP server '{}': {}", namespace, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Converts a single MCP tool definition to our Tool format.
     */
    private Tool convertMcpTool(McpSchema.Tool mcpTool) {
        try {
            String prefixedName = namespace + "_" + sanitizeToolName(mcpTool.name());
            String description = mcpTool.description() != null
                    ? mcpTool.description()
                    : "MCP tool: " + mcpTool.name();

            Map<String, Object> inputSchema = convertInputSchema(mcpTool.inputSchema());

            // Store mapping: sanitized prefixed name → original MCP tool name
            // This is critical for callTool dispatch — MCP server expects original name
            toolNameMapping.put(prefixedName, mcpTool.name());

            return new Tool(prefixedName, description, inputSchema);

        } catch (Exception e) {
            log.warn("Failed to convert MCP tool '{}': {}", mcpTool.name(), e.getMessage());
            return null;
        }
    }

    // --- Schema Conversion ---

    /**
     * Converts MCP input schema to Claude-compatible JSON Schema format.
     * MCP uses standard JSON Schema, which Claude also expects — minimal conversion needed.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertInputSchema(McpSchema.JsonSchema mcpSchema) {
        if (mcpSchema == null) {
            // No input schema → empty object schema
            return Map.of("type", "object", "properties", Map.of());
        }

        try {
            // MCP JsonSchema maps closely to Claude's expected format
            // Convert via Jackson to get a clean Map representation
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");

            if (mcpSchema.properties() != null) {
                schema.put("properties", mcpSchema.properties());
            } else {
                schema.put("properties", Map.of());
            }

            if (mcpSchema.required() != null) {
                schema.put("required", mcpSchema.required());
            }

            // Preserve additionalProperties if specified
            if (mcpSchema.additionalProperties() != null) {
                schema.put("additionalProperties", mcpSchema.additionalProperties());
            }

            return schema;
        } catch (Exception e) {
            log.warn("Failed to convert MCP schema, using empty schema: {}", e.getMessage());
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    // --- Result Conversion ---

    /**
     * Converts MCP CallToolResult to our ToolResult format.
     * Extracts text content from MCP's content block array.
     */
    private ToolResult convertResult(String toolUseId, McpSchema.CallToolResult mcpResult) {
        if (mcpResult == null) {
            return ToolResult.success(toolUseId, "");
        }

        boolean isError = Boolean.TRUE.equals(mcpResult.isError());

        // Extract text content from MCP result content blocks
        String content = extractTextContent(mcpResult);

        return isError
                ? ToolResult.error(toolUseId, content)
                : ToolResult.success(toolUseId, content);
    }

    /**
     * Extracts text from MCP content blocks.
     * MCP results contain a list of content blocks (text, image, resource).
     * We concatenate all text blocks for the tool result.
     */
    private String extractTextContent(McpSchema.CallToolResult mcpResult) {
        if (mcpResult.content() == null || mcpResult.content().isEmpty()) {
            return "";
        }

        return mcpResult.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    // --- Utility ---

    /**
     * Sanitizes an MCP tool name for use as a Claude tool name.
     * Replaces characters not allowed in Claude tool names (only alphanumeric + underscore).
     */
    static String sanitizeToolName(String mcpName) {
        if (mcpName == null) return "unknown";
        // Replace hyphens and dots with underscores, remove other special chars
        return mcpName.replaceAll("[^a-zA-Z0-9_]", "_")
                .replaceAll("_+", "_")  // collapse multiple underscores
                .replaceAll("^_|_$", ""); // trim leading/trailing underscores
    }

    /**
     * Strips the namespace prefix from a tool call name.
     * "monitoring_search_logs" → "search_logs"
     * Then un-sanitizes back to original MCP name if needed.
     */
    private String stripNamespacePrefix(String toolName) {
        String prefix = namespace + "_";
        if (toolName.startsWith(prefix)) {
            return toolName.substring(prefix.length());
        }
        return toolName;
    }

    /**
     * Converts a JsonNode input to a Map for the MCP SDK.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertInputToMap(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(input, Map.class);
        } catch (Exception e) {
            log.warn("Failed to convert tool input to map: {}", e.getMessage());
            return Map.of();
        }
    }

    /** Returns the underlying MCP client (for health checks / lifecycle). */
    public McpSyncClient getMcpClient() {
        return mcpClient;
    }

    /** Returns the original MCP tool name for a given prefixed name (for testing / debugging). */
    public String getOriginalToolName(String prefixedName) {
        return toolNameMapping.get(prefixedName);
    }

    @Override
    public String toString() {
        return "McpBridgeToolProvider{namespace='" + namespace + "', tools=" + cachedTools.size() + "}";
    }
}
