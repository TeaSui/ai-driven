package com.aidriven.lambda;

import com.aidriven.claude.ClaudeClient;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.CodeContextS3Service;
import com.aidriven.core.service.SecretsService;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ClaudeInvokeHandlerTest {

    @Mock
    private TicketStateRepository ticketStateRepository;

    @Mock
    private SecretsService secretsService;

    @Mock
    private CodeContextS3Service codeContextS3Service;

    @Mock
    private Context lambdaContext;

    private ClaudeInvokeHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CLAUDE_SECRET = "arn:aws:secretsmanager:test:claude";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ClaudeInvokeHandler(
                objectMapper, ticketStateRepository, secretsService,
                CLAUDE_SECRET, codeContextS3Service);
    }

    @Test
    void should_throw_for_null_input() {
        assertThrows(NullPointerException.class,
                () -> handler.handleRequest(null, lambdaContext));
    }

    @Test
    void should_throw_for_empty_input() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.handleRequest(Map.of(), lambdaContext));
    }

    @Test
    void should_throw_for_missing_ticket_id() {
        Map<String, Object> input = Map.of("ticketKey", "PROJ-1");

        assertThrows(IllegalArgumentException.class,
                () -> handler.handleRequest(input, lambdaContext));
    }

    @Test
    void should_throw_for_missing_ticket_key() {
        Map<String, Object> input = Map.of("ticketId", "12345");

        assertThrows(IllegalArgumentException.class,
                () -> handler.handleRequest(input, lambdaContext));
    }

    @Test
    void should_invoke_claude_and_return_files() throws Exception {
        String claudeResponse = """
                Here is the code:
                {"files": [{"path": "src/Main.java", "content": "public class Main {}", "operation": "CREATE"}], "commitMessage": "feat: add Main", "prTitle": "Add Main class", "prDescription": "Added Main.java"}
                """;

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(claudeResponse))) {

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals("12345", result.get("ticketId"));
            assertEquals("PROJ-1", result.get("ticketKey"));
            assertEquals(true, result.get("success"));
            assertEquals("feat: add Main", result.get("commitMessage"));
            assertEquals("Add Main class", result.get("prTitle"));
            assertEquals("claude-opus", result.get("agentType"));

            String filesJson = (String) result.get("files");
            assertTrue(filesJson.contains("Main.java"));
        }
    }

    @Test
    void should_handle_missing_s3_key_gracefully() throws Exception {
        String claudeResponse = """
                {"files": [], "commitMessage": "feat: empty", "prTitle": "Empty PR"}
                """;

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(claudeResponse))) {

            Map<String, Object> input = buildValidInput();
            input.remove("codeContextS3Key");

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(true, result.get("success"));
            verifyNoInteractions(codeContextS3Service);
        }
    }

    @Test
    void should_read_code_context_from_s3() throws Exception {
        String claudeResponse = """
                {"files": [], "commitMessage": "feat: test"}
                """;
        String codeContext = "=== SOURCE FILES ===\n--- File: Main.java ---\npublic class Main {}";

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");
        when(codeContextS3Service.retrieveContext("context/PROJ-1/123.txt")).thenReturn(codeContext);

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(claudeResponse))) {

            Map<String, Object> input = buildValidInput();
            input.put("codeContextS3Key", "context/PROJ-1/123.txt");

            handler.handleRequest(input, lambdaContext);

            verify(codeContextS3Service).retrieveContext("context/PROJ-1/123.txt");
        }
    }

    @Test
    void should_throw_for_invalid_api_key() {
        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("invalid-key");

        Map<String, Object> input = buildValidInput();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.handleRequest(input, lambdaContext));

        assertTrue(ex.getMessage().contains("Claude invocation failed"));
        verify(ticketStateRepository, atLeast(1)).save(any());
    }

    @Test
    void should_throw_for_null_api_key() {
        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn(null);

        Map<String, Object> input = buildValidInput();

        assertThrows(RuntimeException.class,
                () -> handler.handleRequest(input, lambdaContext));
    }

    @Test
    void should_handle_claude_response_with_no_json() throws Exception {
        String claudeResponse = "I cannot generate code for this request.";

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(claudeResponse))) {

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(false, result.get("success"));
        }
    }

    @Test
    void should_pass_through_dry_run_flag() throws Exception {
        String claudeResponse = """
                {"files": [], "commitMessage": "test"}
                """;

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(claudeResponse))) {

            Map<String, Object> input = buildValidInput();
            input.put("dryRun", true);

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(true, result.get("dryRun"));
        }
    }

    @Test
    void should_update_state_to_failed_on_claude_error() throws Exception {
        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString()))
                        .thenThrow(new RuntimeException("API error")))) {

            Map<String, Object> input = buildValidInput();

            assertThrows(RuntimeException.class,
                    () -> handler.handleRequest(input, lambdaContext));

            verify(ticketStateRepository, atLeast(1)).save(any());
        }
    }

    @Test
    void should_handle_base64_encoded_file_content() throws Exception {
        String base64Content = java.util.Base64.getEncoder().encodeToString("hello world".getBytes());
        String claudeResponse = String.format("""
                {"files": [{"path": "test.txt", "contentBase64": "%s", "operation": "CREATE"}], "commitMessage": "feat: test"}
                """, base64Content);

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(claudeResponse))) {

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(true, result.get("success"));
            String filesJson = (String) result.get("files");
            assertTrue(filesJson.contains("hello world"));
        }
    }

    @Test
    void should_repair_truncated_json_response() throws Exception {
        // Simulates a response truncated mid-content (missing closing brackets)
        String truncatedResponse = """
                {"files": [{"path": "src/Main.java", "content": "public class Main {\\n    public void run() {\\n        System.out.println(\\"hello\\");""";

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(truncatedResponse))) {

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(true, result.get("success"));
            String filesJson = (String) result.get("files");
            assertTrue(filesJson.contains("Main.java"));
        }
    }

    @Test
    void should_repair_json_with_corruption_at_stitch_boundary() throws Exception {
        // Simulates stitching corruption where extra text appears between JSON fields
        String corruptedResponse = """
                {"files": [{"path": "src/Main.java", "content": "code", "operation": "CREATE"}], "commitHere is the continuation:Message": "feat: add"}""";

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(corruptedResponse))) {

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            // Should still extract the files even if commitMessage is lost
            assertEquals(true, result.get("success"));
            String filesJson = (String) result.get("files");
            assertTrue(filesJson.contains("Main.java"));
        }
    }

    @Test
    void should_handle_missing_operation_field_gracefully() throws Exception {
        // File without operation field should default to CREATE
        String response = """
                {"files": [{"path": "src/Test.java", "content": "public class Test {}"}], "commitMessage": "feat: test"}""";

        when(secretsService.getSecretString(CLAUDE_SECRET)).thenReturn("sk-test-key-123");

        try (MockedConstruction<ClaudeClient> mockedClaude = mockConstruction(ClaudeClient.class,
                (mock, ctx) -> when(mock.chat(anyString(), anyString())).thenReturn(response))) {

            Map<String, Object> input = buildValidInput();

            Map<String, Object> result = handler.handleRequest(input, lambdaContext);

            assertEquals(true, result.get("success"));
            String filesJson = (String) result.get("files");
            assertTrue(filesJson.contains("Test.java"));
        }
    }

    @Test
    void parseJsonWithRepair_should_handle_valid_json() {
        String validJson = "{\"files\": [], \"commitMessage\": \"test\"}";
        var node = handler.parseJsonWithRepair(validJson);
        assertNotNull(node);
        assertTrue(node.has("files"));
        assertEquals("test", node.get("commitMessage").asText());
    }

    @Test
    void parseJsonWithRepair_should_close_truncated_json() {
        String truncated = "{\"files\": [{\"path\": \"a.java\", \"content\": \"code";
        var node = handler.parseJsonWithRepair(truncated);
        assertNotNull(node);
        assertTrue(node.has("files"));
    }

    @Test
    void parseJsonWithRepair_should_handle_trailing_comma() {
        String trailingComma = "{\"files\": [{\"path\": \"a.java\", \"content\": \"code\", \"operation\": \"CREATE\"},";
        var node = handler.parseJsonWithRepair(trailingComma);
        assertNotNull(node);
        assertTrue(node.has("files"));
    }

    @Test
    void closeJsonString_should_close_open_constructs() {
        String open = "{\"key\": \"val";
        String closed = handler.closeJsonString(open);
        assertEquals("{\"key\": \"val\"}", closed);
    }

    @Test
    void closeJsonString_should_handle_nested_constructs() {
        String nested = "{\"arr\": [{\"k\": \"v";
        String closed = handler.closeJsonString(nested);
        assertEquals("{\"arr\": [{\"k\": \"v\"}]}", closed);
    }

    @Test
    void closeJsonString_should_handle_escaped_quotes_in_strings() {
        String escaped = "{\"content\": \"line1\\\"line2";
        String closed = handler.closeJsonString(escaped);
        assertEquals("{\"content\": \"line1\\\"line2\"}", closed);
    }

    @Test
    void closeJsonString_should_strip_trailing_comma() {
        String trailingComma = "{\"files\": [1, 2,";
        String closed = handler.closeJsonString(trailingComma);
        assertEquals("{\"files\": [1, 2]}", closed);
    }

    private Map<String, Object> buildValidInput() {
        Map<String, Object> input = new HashMap<>();
        input.put("ticketId", "12345");
        input.put("ticketKey", "PROJ-1");
        input.put("summary", "Fix login bug");
        input.put("description", "Users cannot log in");
        input.put("labels", List.of("ai-generate"));
        input.put("priority", "High");
        return input;
    }
}
