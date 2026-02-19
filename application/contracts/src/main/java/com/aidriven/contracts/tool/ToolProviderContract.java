package com.aidriven.contracts.tool;

import java.util.List;
import java.util.Map;

/**
 * Contract for exposing capabilities as tools for AI agents.
 * <p>
 * This is the SPI (Service Provider Interface) that third-party integrations
 * implement to add new tool capabilities. Each implementation wraps a
 * domain-specific client and bridges it to the AI agent's tool-use protocol.
 * </p>
 *
 * <p>
 * Adding a new integration = one new ToolProviderContract implementation
 * + register via ServiceLoader or ToolRegistry.
 * </p>
 */
public interface ToolProviderContract {

    /** Namespace prefix for all tools (e.g., "source_control", "monitoring"). */
    String namespace();

    /** Tool definitions in Claude Messages API format. */
    List<ToolDefinition> toolDefinitions();

    /** Dispatch a tool call to the underlying client. */
    ToolExecutionResult execute(String toolCallId, String toolName, Map<String, Object> input);

    /**
     * Maximum output characters for tool results.
     * Override for domain-specific limits.
     */
    default int maxOutputChars() {
        return 20_000;
    }

    /**
     * Represents a tool definition.
     */
    record ToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema) {

        /** Converts to Claude API format. */
        public Map<String, Object> toApiFormat() {
            return Map.of(
                    "name", name,
                    "description", description,
                    "input_schema", inputSchema);
        }
    }

    /**
     * Result of executing a tool call.
     */
    record ToolExecutionResult(
            String toolUseId,
            String content,
            boolean isError) {

        public static ToolExecutionResult success(String toolUseId, String content) {
            return new ToolExecutionResult(toolUseId, content != null ? content : "", false);
        }

        public static ToolExecutionResult error(String toolUseId, String message) {
            return new ToolExecutionResult(toolUseId, message != null ? message : "Unknown error", true);
        }
    }
}
