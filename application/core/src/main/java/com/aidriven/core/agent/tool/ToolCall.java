package com.aidriven.core.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents a tool call from Claude's response.
 * Parsed from a "tool_use" content block in the Messages API response.
 *
 * @param id    Unique identifier for this tool call (used to correlate with
 *              ToolResult)
 * @param name  Tool name (e.g., "source_control_create_branch")
 * @param input The input arguments as a JSON node
 */
public record ToolCall(String id, String name, JsonNode input) {
}
