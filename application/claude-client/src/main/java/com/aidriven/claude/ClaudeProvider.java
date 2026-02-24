package com.aidriven.claude;

/**
 * Provider enum for Claude AI model interactions.
 * Determines which underlying service is used for Claude API calls.
 */
public enum ClaudeProvider {
    /**
     * Anthropic's direct API (api.anthropic.com).
     */
    ANTHROPIC_API,

    /**
     * AWS BedRock Runtime (bedrock-runtime.*.amazonaws.com).
     */
    BEDROCK;

    /**
     * Parses a string value to ClaudeProvider enum.
     * Defaults to ANTHROPIC_API for null or invalid values.
     *
     * @param value String value to parse
     * @return ClaudeProvider enum
     */
    public static ClaudeProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return ANTHROPIC_API;
        }
        try {
            return ClaudeProvider.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ANTHROPIC_API;
        }
    }
}
