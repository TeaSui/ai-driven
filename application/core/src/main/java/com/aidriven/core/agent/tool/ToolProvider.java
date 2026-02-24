package com.aidriven.core.agent.tool;

import com.aidriven.spi.model.OperationContext;
import java.util.List;

/**
 * Contract for exposing domain client capabilities as Claude tool-use tools.
 * Each implementation wraps a typed domain interface (SourceControlClient,
 * IssueTrackerClient, etc.) and bridges it to Claude's tool-use protocol.
 *
 * <p>
 * Adding a new integration = one new ToolProvider class + register in
 * ToolRegistry.
 * Zero changes to AgentOrchestrator or ToolRegistry.
 * </p>
 *
 * <p>
 * Each ToolProvider maps 1:1 to a future MCP server:
 * <ul>
 * <li>{@code namespace()} → MCP server name</li>
 * <li>{@code toolDefinitions()} → MCP tool schemas</li>
 * <li>{@code execute()} → MCP tool handler</li>
 * </ul>
 */
public interface ToolProvider {

    /** Namespace prefix for all tools (e.g., "source_control", "monitoring"). */
    String namespace();

    /** Tool definitions in Claude Messages API format. */
    List<Tool> toolDefinitions();

    /** Dispatch a tool call to the underlying typed client. */
    ToolResult execute(OperationContext context, ToolCall call);

    /**
     * Maximum output characters for tool results.
     * The ToolRegistry enforces truncation after dispatch.
     * Override for domain-specific limits (e.g., logs: 50k, queries: 10k).
     */
    default int maxOutputChars() {
        return 20_000;
    }
}
