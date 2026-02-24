package com.aidriven.lambda;

import com.aidriven.claude.ClaudeClient;
import com.aidriven.core.agent.AiClient;
import com.aidriven.core.cost.BudgetTracker;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.ContextStorageService;
import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.util.JsonRepairService;
import com.aidriven.core.audit.AuditService;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
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
    private ContextStorageService contextStorageService;

    @Mock
    private GenerationMetricsRepository metricsRepository;

    @Mock
    private AiClient claudeClient;

    @Mock
    private Context lambdaContext;

    @Mock
    private JsonRepairService jsonRepairService;

    @Mock
    private AuditService auditService;

    @Mock
    private BudgetTracker budgetTracker;

    private ClaudeInvokeHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ClaudeInvokeHandler(
                objectMapper, ticketStateRepository, metricsRepository,
                contextStorageService, auditService, claudeClient, jsonRepairService,
                700_000, "v1", true, 100.0, 200_000, budgetTracker);
    }

    @Test
    void should_throw_for_null_input() {
        assertThrows(NullPointerException.class,
                () -> handler.handleRequest(null, lambdaContext));
    }

    @Test
    void should_invoke_claude_and_return_files() throws Exception {
        String claudeResponse = """
                Here is the code:
                {"files": [{"path": "src/Main.java", "content": "public class Main {}", "operation": "CREATE"}], "commitMessage": "feat: add Main", "prTitle": "Add Main class", "prDescription": "Added Main.java"}
                """;

        when(claudeClient.getModel()).thenReturn("claude-opus-4-6");
        when(claudeClient.chat(anyString(), anyString())).thenReturn(claudeResponse);

        Map<String, Object> input = buildValidInput();

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertNotNull(result);
        assertEquals("PROJ-1", result.get("ticketKey"));
        assertNotNull(result.get("files"));

        verify(claudeClient).chat(anyString(), anyString());
        verify(ticketStateRepository, atLeastOnce()).save(any());
    }

    @Test
    void should_use_model_override_if_provided() throws Exception {
        String claudeResponse = "{\"files\": [], \"commitMessage\": \"test\"}";
        String resolvedModel = "claude-3-5-sonnet";

        ClaudeClient overriddenClient = mock(ClaudeClient.class);
        when(claudeClient.withModel(resolvedModel)).thenReturn(overriddenClient);
        when(overriddenClient.getModel()).thenReturn(resolvedModel);
        when(overriddenClient.chat(anyString(), anyString())).thenReturn(claudeResponse);

        Map<String, Object> input = new HashMap<>(buildValidInput());
        input.put("resolvedModel", resolvedModel);

        handler.handleRequest(input, lambdaContext);

        verify(claudeClient).withModel(resolvedModel);
        verify(overriddenClient).chat(anyString(), anyString());
    }

    @Test
    void should_handle_claude_api_error() throws Exception {
        when(claudeClient.chat(anyString(), anyString())).thenThrow(new RuntimeException("API Error"));

        Map<String, Object> input = buildValidInput();

        assertThrows(RuntimeException.class, () -> handler.handleRequest(input, lambdaContext));
        verify(ticketStateRepository, atLeastOnce()).save(any());
    }

    @Test
    void should_load_context_from_s3_if_provided() throws Exception {
        String s3Key = "context/test.txt";
        String s3Content = "existing code context";
        when(contextStorageService.getContext(s3Key)).thenReturn(s3Content);

        when(claudeClient.getModel()).thenReturn("test-model");
        when(claudeClient.chat(anyString(), anyString())).thenReturn("{\"files\": []}");

        Map<String, Object> input = new HashMap<>(buildValidInput());
        input.put("codeContextS3Key", s3Key);

        handler.handleRequest(input, lambdaContext);

        verify(contextStorageService).getContext(s3Key);
    }

    private Map<String, Object> buildValidInput() {
        return Map.of(
                "ticketId", "12345",
                "ticketKey", "PROJ-1",
                "summary", "Test ticket",
                "description", "A test ticket description",
                "labels", List.of("backend", "ai-refactoring"),
                "priority", "High");
    }
}
