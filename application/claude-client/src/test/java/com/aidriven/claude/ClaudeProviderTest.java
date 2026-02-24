package com.aidriven.claude;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClaudeProvider enum.
 */
class ClaudeProviderTest {

    @Test
    void testFromString_anthropicApi() {
        assertEquals(ClaudeProvider.ANTHROPIC_API, ClaudeProvider.fromString("ANTHROPIC_API"));
        assertEquals(ClaudeProvider.ANTHROPIC_API, ClaudeProvider.fromString("anthropic_api"));
        assertEquals(ClaudeProvider.ANTHROPIC_API, ClaudeProvider.fromString("Anthropic_Api"));
    }

    @Test
    void testFromString_bedrock() {
        assertEquals(ClaudeProvider.BEDROCK, ClaudeProvider.fromString("BEDROCK"));
        assertEquals(ClaudeProvider.BEDROCK, ClaudeProvider.fromString("bedrock"));
        assertEquals(ClaudeProvider.BEDROCK, ClaudeProvider.fromString("Bedrock"));
    }

    @Test
    void testFromString_null_defaultsToAnthropic() {
        assertEquals(ClaudeProvider.ANTHROPIC_API, ClaudeProvider.fromString(null));
    }

    @Test
    void testFromString_empty_defaultsToAnthropic() {
        assertEquals(ClaudeProvider.ANTHROPIC_API, ClaudeProvider.fromString(""));
        assertEquals(ClaudeProvider.ANTHROPIC_API, ClaudeProvider.fromString("   "));
    }

    @Test
    void testFromString_invalid_defaultsToAnthropic() {
        assertEquals(ClaudeProvider.ANTHROPIC_API, ClaudeProvider.fromString("invalid"));
        assertEquals(ClaudeProvider.ANTHROPIC_API, ClaudeProvider.fromString("openai"));
    }
}
