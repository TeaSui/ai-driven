package com.aidriven.contracts.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProviderContractTest {

    @Test
    void toolDefinition_toApiFormat_should_produce_correct_structure() {
        var def = new ToolProviderContract.ToolDefinition(
                "source_control_get_file",
                "Get file content",
                Map.of("type", "object",
                        "properties", Map.of("path", Map.of("type", "string")),
                        "required", java.util.List.of("path")));

        Map<String, Object> api = def.toApiFormat();

        assertEquals("source_control_get_file", api.get("name"));
        assertEquals("Get file content", api.get("description"));
        assertNotNull(api.get("input_schema"));
    }

    @Test
    void toolExecutionResult_success_should_not_be_error() {
        var result = ToolProviderContract.ToolExecutionResult.success("id-1", "file content");

        assertEquals("id-1", result.toolUseId());
        assertEquals("file content", result.content());
        assertFalse(result.isError());
    }

    @Test
    void toolExecutionResult_success_null_content_should_default_to_empty() {
        var result = ToolProviderContract.ToolExecutionResult.success("id-1", null);

        assertEquals("", result.content());
    }

    @Test
    void toolExecutionResult_error_should_be_error() {
        var result = ToolProviderContract.ToolExecutionResult.error("id-1", "Not found");

        assertEquals("id-1", result.toolUseId());
        assertEquals("Not found", result.content());
        assertTrue(result.isError());
    }

    @Test
    void toolExecutionResult_error_null_message_should_default() {
        var result = ToolProviderContract.ToolExecutionResult.error("id-1", null);

        assertEquals("Unknown error", result.content());
    }

    @Test
    void toolProviderContract_default_maxOutputChars() {
        ToolProviderContract provider = new ToolProviderContract() {
            public String namespace() { return "test"; }
            public java.util.List<ToolDefinition> toolDefinitions() { return java.util.List.of(); }
            public ToolExecutionResult execute(String id, String name, Map<String, Object> input) {
                return ToolExecutionResult.success(id, "");
            }
        };

        assertEquals(20_000, provider.maxOutputChars());
    }
}
