package com.aidriven.core.agent.tool;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Represents the result of executing a tool call.
 * Sent back to Claude as a "tool_result" content block.
 *
 * @param toolUseId Correlation ID matching the original ToolCall
 * @param content   The result content (text)
 * @param isError   Whether the tool execution failed
 */
public record ToolResult(String toolUseId, String content, boolean isError) {

    /** Claude's required pattern for tool_use_id values. */
    private static final Pattern TOOL_USE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    /** Compact constructor with validation. */
    public ToolResult {
        if (toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalArgumentException("tool_use_id must not be null or blank");
        }
        if (!TOOL_USE_ID_PATTERN.matcher(toolUseId).matches()) {
            throw new IllegalArgumentException(
                    "tool_use_id '" + toolUseId + "' does not match required pattern: " + TOOL_USE_ID_PATTERN);
        }
        if (content == null) {
            content = "";
        }
    }

    public static ToolResult success(String toolUseId, String content) {
        return new ToolResult(toolUseId, content != null ? content : "", false);
    }

    public static ToolResult success(String toolUseId, String format, Object... args) {
        return new ToolResult(toolUseId, String.format(format, args), false);
    }

    public static ToolResult error(String toolUseId, String message) {
        return new ToolResult(toolUseId, message != null ? message : "Unknown error", true);
    }

    /** Converts this result to Claude API "tool_result" content block format. */
    public Map<String, Object> toContentBlock() {
        Map<String, Object> block = new java.util.LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", content);
        if (isError) {
            block.put("is_error", true);
        }
        return block;
    }
}
