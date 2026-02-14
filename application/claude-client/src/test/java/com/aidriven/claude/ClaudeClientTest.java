package com.aidriven.claude;

import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeClientTest {

    @Test
    void should_initialize_from_secrets() {
        SecretsService secretsService = mock(SecretsService.class);
        String secretArn = "test-arn";
        String apiKey = "sk-test-key";

        when(secretsService.getSecret(secretArn)).thenReturn(apiKey);

        ClaudeClient client = ClaudeClient.fromSecrets(secretsService, secretArn);

        assertNotNull(client);
    }

    @Test
    void should_throw_when_apiKey_is_null() {
        SecretsService secretsService = mock(SecretsService.class);
        String secretArn = "test-arn";

        when(secretsService.getSecret(secretArn)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ClaudeClient.fromSecrets(secretsService, secretArn));

        assertTrue(ex.getCause().getMessage().contains("empty or missing"));
    }

    @Test
    void should_throw_when_apiKey_is_empty() {
        SecretsService secretsService = mock(SecretsService.class);
        String secretArn = "test-arn";

        when(secretsService.getSecret(secretArn)).thenReturn("  ");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ClaudeClient.fromSecrets(secretsService, secretArn));

        assertTrue(ex.getCause().getMessage().contains("empty or missing"));
    }

    @Test
    void should_skip_content_blocks_without_type_field() throws Exception {
        // Given: A JSON response with a content block missing the "type" field
        ClaudeClient client = new ClaudeClient("sk-test-key");
        ObjectMapper mapper = new ObjectMapper();
        String jsonStr = """
                {
                    "content": [
                        {"text": "should be skipped"},
                        {"type": "text", "text": "included text"},
                        {"type": "text", "text": " more text"}
                    ]
                }
                """;
        JsonNode json = mapper.readTree(jsonStr);

        // When: extractTextContent is invoked via reflection
        String result = invokeExtractTextContent(client, json);

        // Then: Only blocks with type="text" should be included
        assertEquals("included text more text", result);
    }

    @Test
    void should_return_empty_when_all_blocks_lack_type() throws Exception {
        // Given: All content blocks are missing the "type" field
        ClaudeClient client = new ClaudeClient("sk-test-key");
        ObjectMapper mapper = new ObjectMapper();
        String jsonStr = """
                {
                    "content": [
                        {"text": "no type here"},
                        {"text": "also no type"}
                    ]
                }
                """;
        JsonNode json = mapper.readTree(jsonStr);

        // When: extractTextContent is invoked
        String result = invokeExtractTextContent(client, json);

        // Then: Result should be empty since no blocks have type="text"
        assertEquals("", result);
    }

    @Test
    void should_handle_non_text_type_blocks() throws Exception {
        // Given: A response with mixed content block types
        ClaudeClient client = new ClaudeClient("sk-test-key");
        ObjectMapper mapper = new ObjectMapper();
        String jsonStr = """
                {
                    "content": [
                        {"type": "image", "source": {}},
                        {"type": "text", "text": "hello world"}
                    ]
                }
                """;
        JsonNode json = mapper.readTree(jsonStr);

        // When: extractTextContent is invoked
        String result = invokeExtractTextContent(client, json);

        // Then: Only text-type blocks are extracted
        assertEquals("hello world", result);
    }

    private String invokeExtractTextContent(ClaudeClient client, JsonNode json) throws Exception {
        Method method = ClaudeClient.class.getDeclaredMethod("extractTextContent", JsonNode.class);
        method.setAccessible(true);
        return (String) method.invoke(client, json);
    }
}
