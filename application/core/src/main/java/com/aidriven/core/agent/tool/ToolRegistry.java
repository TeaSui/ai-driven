package com.aidriven.core.agent.tool;

import com.aidriven.spi.model.OperationContext;
import com.aidriven.core.model.TicketInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Central registry that aggregates all registered {@link ToolProvider}s and
 * routes incoming tool calls to the correct provider by namespace prefix.
 *
 * <p>This class is the single entry point for tool management in the agent
 * orchestration loop. It handles three key responsibilities:</p>
 * <ol>
 *   <li><strong>Registration</strong> -- providers register themselves via
 *       {@link #register(ToolProvider)}, keyed by their namespace</li>
 *   <li><strong>Discovery</strong> -- tool definitions are collected across all
 *       providers and filtered by ticket context via
 *       {@link #getAvailableTools(TicketInfo)}</li>
 *   <li><strong>Routing and execution</strong> -- incoming tool calls are
 *       dispatched to the matching provider via {@link #execute(OperationContext, ToolCall)},
 *       with automatic output truncation and error handling</li>
 * </ol>
 *
 * <h3>Provider Enabling Rules</h3>
 * <p>Core providers ({@code source_control}, {@code issue_tracker},
 * {@code code_context}) are always enabled. Optional providers (e.g.,
 * {@code monitoring}) are enabled per-ticket via labels: a ticket with
 * label {@code "tool:monitoring"} enables the {@code monitoring} provider.</p>
 *
 * <h3>Namespace Prefix Matching</h3>
 * <p>When a tool call arrives (e.g., {@code "source_control_create_branch"}),
 * the registry extracts the namespace by finding the longest registered prefix
 * that matches the tool name. This supports nested namespaces without ambiguity.</p>
 *
 * <h3>Output Truncation</h3>
 * <p>After execution, tool results are truncated to the provider's
 * {@link ToolProvider#maxOutputChars()} limit to prevent exceeding the AI
 * model's context window. Truncated output includes a marker indicating the
 * original length.</p>
 *
 * @see ToolProvider
 * @see ToolCall
 * @see ToolResult
 * @since 1.0
 */
@Slf4j
public class ToolRegistry {

    private static final Set<String> CORE_NAMESPACES = Set.of("source_control", "issue_tracker", "code_context");

    private final Map<String, ToolProvider> providers = new LinkedHashMap<>();

    /**
     * Registers a tool provider, making its tools available for discovery and
     * execution.
     *
     * <p>If a provider with the same namespace is already registered, it is
     * replaced. Null providers are ignored with a warning log.</p>
     *
     * @param provider the tool provider to register; if {@code null}, the call
     *                 is silently ignored with a warning
     */
    public void register(ToolProvider provider) {
        if (provider == null) {
            log.warn("Attempted to register null ToolProvider");
            return;
        }
        providers.put(provider.namespace(), provider);
        log.info("Registered tool provider: {} ({} tools)",
                provider.namespace(), provider.toolDefinitions().size());
    }

    /**
     * Returns all tool definitions from all registered providers, regardless
     * of whether they are enabled for a specific ticket.
     *
     * <p>This is primarily useful for administrative purposes, testing, and
     * debugging. For ticket-scoped tool lists, use
     * {@link #getAvailableTools(TicketInfo)}.</p>
     *
     * @return an unmodifiable list of all registered tool definitions;
     *         never {@code null}
     */
    public List<Tool> getAllToolDefinitions() {
        return providers.values().stream()
                .flatMap(p -> p.toolDefinitions().stream())
                .toList();
    }

    /**
     * Returns tool definitions filtered by the given ticket's configuration.
     *
     * <p>Core providers ({@code source_control}, {@code issue_tracker},
     * {@code code_context}) are always included. Optional providers are included
     * only if the ticket has a label matching {@code "tool:{namespace}"}
     * (case-insensitive).</p>
     *
     * @param ticket the ticket whose labels determine which optional providers
     *               are active; may be {@code null}, in which case only core
     *               providers are included
     * @return an unmodifiable list of available tool definitions for the ticket
     */
    public List<Tool> getAvailableTools(TicketInfo ticket) {
        return providers.entrySet().stream()
                .filter(e -> isProviderEnabled(e.getKey(), ticket))
                .flatMap(e -> e.getValue().toolDefinitions().stream())
                .toList();
    }

    /**
     * Routes a tool call to the correct provider by namespace prefix, executes
     * it, and returns the result with automatic truncation and error handling.
     *
     * <p>The routing process:
     * <ol>
     *   <li>Extract the namespace from the tool name using longest-prefix matching</li>
     *   <li>Look up the registered provider for that namespace</li>
     *   <li>Delegate execution to the provider</li>
     *   <li>Truncate the result if it exceeds the provider's
     *       {@link ToolProvider#maxOutputChars()} limit</li>
     * </ol>
     *
     * <p>If no provider is found or execution throws an exception, an error
     * {@link ToolResult} is returned rather than propagating the exception.
     * This ensures the agent loop can always send a valid {@code tool_result}
     * back to the model.</p>
     *
     * @param context the operation context for tracing and multi-tenancy;
     *                never {@code null}
     * @param call    the tool call to execute; never {@code null}
     * @return the execution result, possibly truncated; never {@code null}
     */
    public ToolResult execute(OperationContext context, ToolCall call) {
        String namespace = extractNamespace(call.name());
        ToolProvider provider = providers.get(namespace);
        if (provider == null) {
            log.warn("No provider registered for tool: {} (namespace: {})", call.name(), namespace);
            return ToolResult.error(call.id(), "Unknown tool: " + call.name());
        }

        log.info("Executing tool: {} (provider: {})", call.name(), namespace);
        try {
            ToolResult result = provider.execute(context, call);
            return truncateIfNeeded(result, provider.maxOutputChars());
        } catch (Exception e) {
            log.error("Tool execution failed: {} - {}", call.name(), e.getMessage(), e);
            return ToolResult.error(call.id(), "Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * Extracts the namespace from a tool name by matching the longest registered
     * prefix followed by an underscore separator.
     *
     * <p>Example: given registered namespaces {@code ["source_control", "source"]},
     * the tool name {@code "source_control_create_branch"} matches
     * {@code "source_control"} (longest match wins).</p>
     *
     * @param toolName the full tool name to parse
     * @return the matched namespace, or {@code "unknown"} if no prefix matches
     */
    String extractNamespace(String toolName) {
        return providers.keySet().stream()
                .filter(ns -> toolName.startsWith(ns + "_"))
                .max(Comparator.comparingInt(String::length))
                .orElse("unknown");
    }

    private boolean isProviderEnabled(String namespace, TicketInfo ticket) {
        if (CORE_NAMESPACES.contains(namespace)) {
            return true;
        }
        if (ticket == null || ticket.getLabels() == null) {
            return false;
        }
        return ticket.getLabels().stream()
                .anyMatch(label -> label.equalsIgnoreCase("tool:" + namespace));
    }

    private ToolResult truncateIfNeeded(ToolResult result, int maxChars) {
        if (result.content() == null || result.content().length() <= maxChars) {
            return result;
        }
        log.info("Truncating tool output from {} to {} chars", result.content().length(), maxChars);
        String truncated = result.content().substring(0, maxChars)
                + "\n\n... [OUTPUT TRUNCATED — " + result.content().length() + " total chars]";
        return new ToolResult(result.toolUseId(), truncated, result.isError());
    }

    /**
     * Returns the set of currently registered provider namespaces.
     *
     * <p>Primarily intended for testing and debugging. The returned set is
     * an unmodifiable view.</p>
     *
     * @return an unmodifiable set of namespace strings; never {@code null}
     */
    public Set<String> getRegisteredNamespaces() {
        return Collections.unmodifiableSet(providers.keySet());
    }
}
