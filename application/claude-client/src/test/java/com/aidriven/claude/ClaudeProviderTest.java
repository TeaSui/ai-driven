package com.aidriven.claude;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClaudeProvider enum.
 */
class ClaudeProviderTest {

    @Test
    void should_parse_spring_ai_from_string() {
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString("SPRING_AI"));
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString("spring_ai"));
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString("Spring_Ai"));
    }

    @Test
    void should_parse_bedrock_from_string() {
        assertEquals(ClaudeProvider.BEDROCK, ClaudeProvider.fromString("BEDROCK"));
        assertEquals(ClaudeProvider.BEDROCK, ClaudeProvider.fromString("bedrock"));
        assertEquals(ClaudeProvider.BEDROCK, ClaudeProvider.fromString("Bedrock"));
    }

    @Test
    void should_default_to_spring_ai_when_null() {
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString(null));
    }

    @Test
    void should_default_to_spring_ai_when_empty() {
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString(""));
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString("   "));
    }

    @Test
    void should_default_to_spring_ai_when_invalid() {
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString("invalid"));
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString("openai"));
        assertEquals(ClaudeProvider.SPRING_AI, ClaudeProvider.fromString("ANTHROPIC_API"));
    }

    @Test
    void should_only_have_two_enum_values() {
        assertEquals(2, ClaudeProvider.values().length);
        assertNotNull(ClaudeProvider.valueOf("BEDROCK"));
        assertNotNull(ClaudeProvider.valueOf("SPRING_AI"));
    }
}
