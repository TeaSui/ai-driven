package com.aidriven.core.agent.tool;

import com.aidriven.core.model.TicketInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates all registered {@link ToolProvider}s and routes tool calls
 * to the correct provider by namespace prefix.
 *
 * <p>
 * Core providers (source_control, issue_tracker) are always enabled.
 * Optional providers are enabled per-ticket via labels (e.g.,
 * "tool:monitoring").
 * </p>
 */
@Slf4j
public class ToolRegistry {

    private static final Set<String> CORE_NAMESPACES = Set.of("source_control", "issue_tracker", "code_context");

    private final Map<String, ToolProvider> providers = new LinkedHashMap<>();

    public void register(ToolProvider provider) {
        providers.put(provider.namespace(), provider);
        log.info("Registered tool provider: {} ({} tools)",
                provider.namespace(), provider.toolDefinitions().size());
    }

    /** All tool definitions across all registered providers. */
    public List<Tool> getAllToolDefinitions() {
        return providers.values().stream()
                .flatMap(p -> p.toolDefinitions().stream())
                .toList();
    }

    /**
     * Tools filtered by ticket config (labels determine which optional providers
     * are active).
     */
    public List<Tool> getAvailableTools(TicketInfo ticket) {
        return providers.entrySet().stream()
                .filter(e -> isProviderEnabled(e.getKey(), ticket))
                .flatMap(e -> e.getValue().toolDefinitions().stream())
                .toList();
    }

    /**
     * Route a tool call to the correct provider by namespace prefix, with output
     * truncation.
     */
    public ToolResult execute(ToolCall call) {
        String namespace = extractNamespace(call.name());
        ToolProvider provider = providers.get(namespace);
        if (provider == null) {
            log.warn("No provider registered for tool: {} (namespace: {})", call.name(), namespace);
            return ToolResult.error(call.id(), "Unknown tool: " + call.name());
        }

        log.info("Executing tool: {} (provider: {})", call.name(), namespace);
        try {
            ToolResult result = provider.execute(call);
            return truncateIfNeeded(result, provider.maxOutputChars());
        } catch (Exception e) {
            log.error("Tool execution failed: {} - {}", call.name(), e.getMessage(), e);
            return ToolResult.error(call.id(), "Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * Extracts the namespace from a tool name by matching the longest registered
     * prefix.
     * Example: "source_control_create_branch" → "source_control"
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

    /** Returns the set of registered namespaces (for testing/debugging). */
    public Set<String> getRegisteredNamespaces() {
        return Collections.unmodifiableSet(providers.keySet());
    }
}
