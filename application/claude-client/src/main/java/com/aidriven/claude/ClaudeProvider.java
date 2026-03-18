package com.aidriven.claude;

/**
 * Provider enum for Claude AI model interactions.
 * Determines which underlying service is used for Claude API calls.
 */
public enum ClaudeProvider {

    /**
     * AWS BedRock Runtime (bedrock-runtime.*.amazonaws.com).
     */
    BEDROCK,

    /**
     * Spring AI Anthropic wrapper with retry, prompt caching, and streaming support.
     */
    SPRING_AI;

    /**
     * Parses a string value to ClaudeProvider enum.
     * Defaults to SPRING_AI for null, blank, or invalid values.
     *
     * @param value String value to parse
     * @return ClaudeProvider enum
     */
    public static ClaudeProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return SPRING_AI;
        }
        try {
            return ClaudeProvider.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SPRING_AI;
        }
    }
}
