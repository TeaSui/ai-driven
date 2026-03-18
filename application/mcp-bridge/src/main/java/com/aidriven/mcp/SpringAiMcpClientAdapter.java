package com.aidriven.mcp;

import com.aidriven.core.agent.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.Objects;

/**
 * Adapts {@link McpBridgeToolProvider} into Spring AI's {@link ToolCallbackProvider}
 * contract.
 *
 * <p>This adapter is the Phase 2 bridge between the project's custom MCP integration
 * and Spring AI's native tool abstraction layer. It wraps an existing
 * {@code McpBridgeToolProvider} (which itself wraps the MCP SDK's {@code McpSyncClient})
 * and exposes each discovered MCP tool as a Spring AI {@link ToolCallback}.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 * Spring AI ChatClient
 *   |
 *   v
 * SpringAiMcpClientAdapter (ToolCallbackProvider)
 *   |
 *   +-- SpringAiMcpToolCallback (ToolCallback) per tool
 *         |
 *         v
 *       McpBridgeToolProvider (project ToolProvider)
 *         |
 *         v
 *       McpSyncClient (MCP SDK)
 *         |
 *         v
 *       MCP Server (stdio/HTTP+SSE)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Create from existing MCP bridge provider
 * McpBridgeToolProvider mcpProvider = connectionFactory.createProvider(config);
 * SpringAiMcpClientAdapter adapter = SpringAiMcpClientAdapter.from(mcpProvider);
 *
 * // Use with Spring AI ChatClient
 * ChatResponse response = chatClient.prompt()
 *     .user("Search for recent errors")
 *     .toolCallbacks(adapter)
 *     .call()
 *     .chatResponse();
 * }</pre>
 *
 * <h3>Migration Path</h3>
 * <p>When Spring AI ships native MCP support (e.g., {@code spring-ai-mcp} module),
 * this adapter can be replaced by the official integration. The {@code ToolCallbackProvider}
 * interface remains the same, so all downstream code using Spring AI's tool system
 * will continue to work without changes.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe if the underlying {@code McpBridgeToolProvider} is
 * thread-safe. The MCP SDK's {@code McpSyncClient} is thread-safe, so this holds
 * for standard usage.</p>
 *
 * @see McpBridgeToolProvider
 * @see SpringAiMcpToolCallback
 * @see ToolCallbackProvider
 */
@Slf4j
public class SpringAiMcpClientAdapter implements ToolCallbackProvider {

    private final McpBridgeToolProvider mcpProvider;
    private final ObjectMapper objectMapper;
    private final ToolCallback[] toolCallbacks;

    /**
     * Creates an adapter wrapping the given MCP bridge provider.
     *
     * @param mcpProvider  the MCP bridge tool provider to adapt
     * @param objectMapper JSON mapper for schema serialization
     */
    public SpringAiMcpClientAdapter(McpBridgeToolProvider mcpProvider, ObjectMapper objectMapper) {
        this.mcpProvider = Objects.requireNonNull(mcpProvider, "mcpProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.toolCallbacks = buildToolCallbacks();
        log.info("SpringAiMcpClientAdapter created for namespace '{}' with {} tools",
                mcpProvider.namespace(), toolCallbacks.length);
    }

    /**
     * Factory method: creates an adapter with a default ObjectMapper.
     *
     * @param mcpProvider the MCP bridge tool provider to adapt
     * @return a new adapter instance
     */
    public static SpringAiMcpClientAdapter from(McpBridgeToolProvider mcpProvider) {
        return new SpringAiMcpClientAdapter(mcpProvider, new ObjectMapper());
    }

    /**
     * Factory method: creates an adapter with a custom ObjectMapper.
     *
     * @param mcpProvider  the MCP bridge tool provider to adapt
     * @param objectMapper JSON mapper for schema serialization
     * @return a new adapter instance
     */
    public static SpringAiMcpClientAdapter from(McpBridgeToolProvider mcpProvider, ObjectMapper objectMapper) {
        return new SpringAiMcpClientAdapter(mcpProvider, objectMapper);
    }

    /**
     * Returns all MCP tools as Spring AI ToolCallbacks.
     *
     * <p>The array is computed once at construction time and cached. Each element
     * is a {@link SpringAiMcpToolCallback} wrapping a single MCP tool definition.</p>
     *
     * @return array of ToolCallback instances, one per MCP tool
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks;
    }

    /**
     * Returns the namespace of the underlying MCP bridge provider.
     */
    public String getNamespace() {
        return mcpProvider.namespace();
    }

    /**
     * Returns the number of tools exposed by this adapter.
     */
    public int getToolCount() {
        return toolCallbacks.length;
    }

    /**
     * Returns the underlying MCP bridge provider.
     *
     * <p>Use this to access MCP-specific functionality not exposed through
     * the Spring AI interface (e.g., {@code maxOutputChars()}, original tool
     * name mapping).</p>
     */
    public McpBridgeToolProvider getMcpProvider() {
        return mcpProvider;
    }

    private ToolCallback[] buildToolCallbacks() {
        List<Tool> tools = mcpProvider.toolDefinitions();
        if (tools == null || tools.isEmpty()) {
            log.warn("MCP provider '{}' has no tools to adapt", mcpProvider.namespace());
            return new ToolCallback[0];
        }

        return tools.stream()
                .map(tool -> new SpringAiMcpToolCallback(mcpProvider, tool, objectMapper))
                .toArray(ToolCallback[]::new);
    }

    @Override
    public String toString() {
        return "SpringAiMcpClientAdapter{namespace='" + mcpProvider.namespace()
                + "', tools=" + toolCallbacks.length + "}";
    }
}
