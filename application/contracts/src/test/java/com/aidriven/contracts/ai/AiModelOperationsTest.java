package com.aidriven.contracts.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiModelOperationsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aiToolResponse_hasToolUse_true_when_stop_reason_is_tool_use() {
        var response = new AiModelOperations.AiToolResponse(
                mapper.createArrayNode(), "tool_use", 100, 50);

        assertTrue(response.hasToolUse());
    }

    @Test
    void aiToolResponse_hasToolUse_false_when_stop_reason_is_end_turn() {
        var response = new AiModelOperations.AiToolResponse(
                mapper.createArrayNode(), "end_turn", 100, 50);

        assertFalse(response.hasToolUse());
    }

    @Test
    void aiToolResponse_getText_extracts_text_blocks() {
        ArrayNode blocks = mapper.createArrayNode();
        ObjectNode textBlock = mapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "Hello world");
        blocks.add(textBlock);

        ObjectNode toolBlock = mapper.createObjectNode();
        toolBlock.put("type", "tool_use");
        toolBlock.put("id", "123");
        blocks.add(toolBlock);

        var response = new AiModelOperations.AiToolResponse(blocks, "end_turn", 100, 50);

        assertEquals("Hello world", response.getText());
    }

    @Test
    void aiToolResponse_getText_returns_empty_when_no_text_blocks() {
        var response = new AiModelOperations.AiToolResponse(
                mapper.createArrayNode(), "end_turn", 100, 50);

        assertEquals("", response.getText());
    }

    @Test
    void aiToolResponse_getText_handles_null_content_blocks() {
        var response = new AiModelOperations.AiToolResponse(
                null, "end_turn", 100, 50);

        assertEquals("", response.getText());
    }

    @Test
    void aiToolResponse_totalTokens_sums_input_and_output() {
        var response = new AiModelOperations.AiToolResponse(
                mapper.createArrayNode(), "end_turn", 200, 100);

        assertEquals(300, response.totalTokens());
    }
}
