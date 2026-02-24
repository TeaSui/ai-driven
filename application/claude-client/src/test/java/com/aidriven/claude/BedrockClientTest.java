package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BedrockClient.
 */
class BedrockClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testModelMapping() {
        BedrockClient client = new BedrockClient("claude-opus-4-6");
        assertEquals("claude-opus-4-6", client.getModel());
    }

    @Test
    void testModelMapping_sonnet() {
        BedrockClient client = new BedrockClient("claude-3-5-sonnet-20240620");
        assertEquals("claude-3-5-sonnet-20240620", client.getModel());
    }

    @Test
    void testWithModel_returnsNewInstance() {
        BedrockClient original = new BedrockClient("claude-opus-4-6");
        AiClient modified = original.withModel("claude-3-sonnet-20240229");

        assertNotSame(original, modified);
        assertEquals("claude-3-sonnet-20240229", modified.getModel());
    }

    @Test
    void testWithMaxTokens_returnsNewInstance() {
        BedrockClient original = new BedrockClient("claude-opus-4-6");
        AiClient modified = original.withMaxTokens(8192);

        assertNotSame(original, modified);
    }

    @Test
    void testWithTemperature_returnsNewInstance() {
        BedrockClient original = new BedrockClient("claude-opus-4-6");
        AiClient modified = original.withTemperature(0.5);

        assertNotSame(original, modified);
    }

    @Test
    void testToolUseResponse_record() {
        ArrayNode contentBlocks = objectMapper.createArrayNode();
        contentBlocks.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", "Hello, world!"));
        contentBlocks.add(objectMapper.createObjectNode()
                .put("type", "tool_use")
                .put("id", "tool-123")
                .set("input", objectMapper.createObjectNode()
                        .put("command", "read_file")
                        .put("path", "/path/to/file")));

        AiClient.ToolUseResponse response = new AiClient.ToolUseResponse(
                contentBlocks, "tool_use", 100, 50);

        assertTrue(response.hasToolUse());
        assertEquals("Hello, world!", response.getText());
        assertEquals(150, response.totalTokens());
        assertEquals("tool_use", response.stopReason());
    }

    @Test
    void testToolUseResponse_noToolUse() {
        ArrayNode contentBlocks = objectMapper.createArrayNode();
        contentBlocks.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", "Just a text response"));

        AiClient.ToolUseResponse response = new AiClient.ToolUseResponse(
                contentBlocks, "end_turn", 50, 25);

        assertFalse(response.hasToolUse());
        assertEquals("Just a text response", response.getText());
        assertEquals(75, response.totalTokens());
    }
}
