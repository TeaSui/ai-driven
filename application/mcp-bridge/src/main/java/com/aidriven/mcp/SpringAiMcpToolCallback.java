package com.aidriven.mcp;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.spi.model.OperationContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapts a single tool from an {@link McpBridgeToolProvider} into a Spring AI
 * {@link ToolCallback}.
 *
 * <p>This is Phase 2 groundwork for migrating from the custom {@code ToolProvider}
 * contract to Spring AI's native tool abstraction. Each MCP tool discovered by
 * the bridge is wrapped as an individual {@code ToolCallback}, allowing Spring AI's
 * {@code ChatClient} and {@code ToolCallingManager} to invoke MCP tools directly.</p>
 *
 * <h3>Migration Path (current to Spring AI MCP)</h3>
 * <ol>
 *   <li><b>Current (Phase 1):</b> {@code McpBridgeToolProvider} wraps MCP SDK
 *       {@code McpSyncClient} and exposes tools via project's {@code ToolProvider}
 *       interface. Used by {@code AgentOrchestrator} via {@code ToolRegistry}.</li>
 *   <li><b>Phase 2 (this adapter):</b> {@code SpringAiMcpClientAdapter} wraps
 *       {@code McpBridgeToolProvider} as Spring AI {@code ToolCallbackProvider},
 *       enabling Spring AI ChatClient to call MCP tools. Both paths coexist.</li>
 *   <li><b>Phase 3 (future):</b> When Spring AI ships native MCP client support
 *       (e.g., {@code spring-ai-mcp} module), replace this adapter with the official
 *       Spring AI MCP integration. The {@code ToolCallbackProvider} contract stays
 *       the same, so downstream consumers need no changes.</li>
 * </ol>
 *
 * <h3>Why not wait for Spring AI MCP?</h3>
 * <p>Spring AI 1.1.2 does not include a dedicated MCP module. This adapter lets
 * us start using Spring AI's tool abstraction layer now, with a clean migration
 * path when official MCP support arrives.</p>
 */
@Slf4j
public class SpringAiMcpToolCallback implements ToolCallback {

    private static final String OPERATION_CONTEXT_KEY = "operationContext";

    private final McpBridgeToolProvider mcpProvider;
    private final Tool tool;
    private final ToolDefinition toolDefinition;
    private final ObjectMapper objectMapper;

    /**
     * Creates a ToolCallback for a single MCP tool.
     *
     * @param mcpProvider the MCP bridge provider that owns this tool
     * @param tool        the tool definition from the MCP bridge
     * @param objectMapper JSON mapper for serializing schemas and parsing inputs
     */
    SpringAiMcpToolCallback(McpBridgeToolProvider mcpProvider, Tool tool, ObjectMapper objectMapper) {
        this.mcpProvider = Objects.requireNonNull(mcpProvider, "mcpProvider");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.toolDefinition = buildToolDefinition(tool);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    /**
     * Executes the MCP tool with the given JSON input string.
     *
     * <p>Delegates to the underlying {@link McpBridgeToolProvider#execute} method,
     * converting between Spring AI's string-based I/O and the project's
     * {@code ToolCall}/{@code ToolResult} contract.</p>
     *
     * @param toolInput JSON string containing the tool arguments
     * @return the tool execution result as a string
     */
    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * Executes the MCP tool with the given JSON input and optional context.
     *
     * <p>If the {@link ToolContext} contains an {@code OperationContext} under the
     * key {@value #OPERATION_CONTEXT_KEY}, it is forwarded to the MCP bridge.
     * Otherwise, a minimal default context is used.</p>
     *
     * @param toolInput   JSON string containing the tool arguments
     * @param toolContext  optional Spring AI context (may contain OperationContext)
     * @return the tool execution result as a string
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        log.debug("SpringAiMcpToolCallback executing tool '{}' with input: {}",
                tool.name(), truncateForLog(toolInput));

        try {
            JsonNode inputNode = parseInput(toolInput);
            OperationContext operationContext = extractOperationContext(toolContext);
            String callId = generateCallId();

            ToolCall toolCall = new ToolCall(callId, tool.name(), inputNode);
            ToolResult result = mcpProvider.execute(operationContext, toolCall);

            if (result.isError()) {
                log.warn("MCP tool '{}' returned error: {}", tool.name(), truncateForLog(result.content()));
            }

            return result.content();
        } catch (Exception e) {
            log.error("Failed to execute MCP tool '{}': {}", tool.name(), e.getMessage(), e);
            return "Error executing tool " + tool.name() + ": " + e.getMessage();
        }
    }

    /**
     * Returns the name of the underlying MCP tool (namespace-prefixed).
     */
    public String getToolName() {
        return tool.name();
    }

    /**
     * Returns the MCP bridge provider backing this callback.
     */
    public McpBridgeToolProvider getMcpProvider() {
        return mcpProvider;
    }

    private ToolDefinition buildToolDefinition(Tool tool) {
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(tool.inputSchema());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize input schema for tool '{}', using empty schema", tool.name());
            schemaJson = "{\"type\":\"object\",\"properties\":{}}";
        }

        return DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(schemaJson)
                .build();
    }

    private JsonNode parseInput(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(toolInput);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool input as JSON, wrapping as raw value: {}", e.getMessage());
            return objectMapper.createObjectNode().put("input", toolInput);
        }
    }

    private OperationContext extractOperationContext(ToolContext toolContext) {
        if (toolContext != null) {
            Map<String, Object> contextMap = toolContext.getContext();
            Object opCtx = contextMap.get(OPERATION_CONTEXT_KEY);
            if (opCtx instanceof OperationContext) {
                return (OperationContext) opCtx;
            }
        }
        return OperationContext.builder()
                .tenantId("spring-ai-adapter")
                .requestId(UUID.randomUUID().toString())
                .build();
    }

    static String generateCallId() {
        return "springai-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String truncateForLog(String value) {
        if (value == null) {
            return "null";
        }
        int maxLogLength = 200;
        return value.length() > maxLogLength
                ? value.substring(0, maxLogLength) + "...[truncated]"
                : value;
    }
}
