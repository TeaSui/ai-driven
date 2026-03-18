package com.aidriven.core.agent.tool;

import com.aidriven.spi.model.OperationContext;
import java.util.List;

/**
 * Contract for exposing domain client capabilities as AI tool-use tools.
 *
 * <p>Each implementation wraps a typed domain interface (e.g.,
 * {@link com.aidriven.core.source.SourceControlClient},
 * {@link com.aidriven.core.tracker.IssueTrackerClient}) and bridges it to
 * the Claude tool-use protocol. Tools are grouped by a namespace prefix that
 * is used by {@link ToolRegistry} to route incoming tool calls to the correct
 * provider.</p>
 *
 * <h3>Extensibility</h3>
 * <p>Adding a new integration requires only:
 * <ol>
 *   <li>Implement a new {@code ToolProvider} class</li>
 *   <li>Register it with {@link ToolRegistry#register(ToolProvider)}</li>
 * </ol>
 * Zero changes to {@code AgentOrchestrator} or {@code ToolRegistry} are needed.</p>
 *
 * <h3>MCP Alignment</h3>
 * <p>Each {@code ToolProvider} maps 1:1 to a future MCP (Model Context Protocol) server:</p>
 * <ul>
 *   <li>{@link #namespace()} maps to MCP server name</li>
 *   <li>{@link #toolDefinitions()} maps to MCP tool schemas</li>
 *   <li>{@link #execute(OperationContext, ToolCall)} maps to MCP tool handler</li>
 * </ul>
 *
 * <h3>Tool Naming Convention</h3>
 * <p>Tool names must be prefixed with the namespace followed by an underscore.
 * For example, a provider with namespace {@code "source_control"} should define
 * tools named {@code "source_control_create_branch"},
 * {@code "source_control_get_file_content"}, etc.</p>
 *
 * @see ToolRegistry
 * @see Tool
 * @see ToolCall
 * @see ToolResult
 * @since 1.0
 */
public interface ToolProvider {

    /**
     * Returns the namespace prefix shared by all tools in this provider.
     *
     * <p>The namespace is used by {@link ToolRegistry} for routing: when a tool
     * call arrives, the registry extracts the namespace from the tool name by
     * matching the longest registered prefix. All tool names returned by
     * {@link #toolDefinitions()} must start with this namespace followed by
     * an underscore separator.</p>
     *
     * @return the namespace string (e.g., {@code "source_control"},
     *         {@code "issue_tracker"}, {@code "monitoring"}); never {@code null}
     */
    String namespace();

    /**
     * Returns the list of tool definitions exposed by this provider.
     *
     * <p>Each {@link Tool} contains a name, description, and JSON Schema for its
     * input parameters. These definitions are sent to the AI model as part of the
     * tool-use request, enabling the model to discover and invoke available tools.</p>
     *
     * <p>The returned list should be deterministic and stable across calls.
     * Tool names must follow the convention: {@code {namespace}_{action_name}}.</p>
     *
     * @return an unmodifiable list of tool definitions; never {@code null} or empty
     */
    List<Tool> toolDefinitions();

    /**
     * Dispatches a tool call to the underlying typed domain client and returns
     * the result.
     *
     * <p>Implementations should parse the tool name (stripping the namespace prefix)
     * to determine which operation to invoke, then extract parameters from
     * {@link ToolCall#input()} and delegate to the appropriate domain client method.
     * Results are returned as {@link ToolResult}, which is sent back to the AI model
     * as a {@code tool_result} content block.</p>
     *
     * <p>Implementations should not throw exceptions for expected error conditions
     * (e.g., resource not found). Instead, return {@link ToolResult#error(String, String)}
     * so the AI model can reason about the failure. Unexpected exceptions are caught
     * by {@link ToolRegistry} and converted to error results automatically.</p>
     *
     * @param context the operation context providing tenant, repository, and tracing
     *                information; never {@code null}
     * @param call    the tool call containing the tool name and input arguments;
     *                never {@code null}
     * @return a {@link ToolResult} containing the execution output or error message
     */
    ToolResult execute(OperationContext context, ToolCall call);

    /**
     * Returns the maximum number of characters allowed in tool result output.
     *
     * <p>{@link ToolRegistry} enforces truncation after dispatching the call:
     * if the result content exceeds this limit, it is truncated with a
     * {@code [OUTPUT TRUNCATED]} marker appended. Override this method to set
     * domain-specific limits based on expected output sizes.</p>
     *
     * <p>Examples of reasonable limits:
     * <ul>
     *   <li>Source control file content: 20,000 chars (default)</li>
     *   <li>Log output: 50,000 chars</li>
     *   <li>Metric queries: 10,000 chars</li>
     * </ul>
     *
     * @return the maximum output length in characters; defaults to {@code 20,000}
     */
    default int maxOutputChars() {
        return 20_000;
    }
}
