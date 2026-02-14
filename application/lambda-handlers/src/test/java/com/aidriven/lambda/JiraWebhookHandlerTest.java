package com.aidriven.lambda;

import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for JiraWebhookHandler covering webhook processing, label validation,
 * idempotency, and error handling.
 */
class JiraWebhookHandlerTest {

    @Mock
    private TicketStateRepository ticketStateRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private JiraClient jiraClient;

    @Mock
    private SfnClient sfnClient;

    @Mock
    private Context lambdaContext;

    private JiraWebhookHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        String stateMachineArn = "arn:aws:states:us-east-1:123456789:stateMachine:test";
        handler = new JiraWebhookHandler(
                objectMapper, ticketStateRepository, idempotencyService,
                jiraClient, sfnClient, stateMachineArn);
    }

    @Test
    void should_return_400_for_empty_body() {
        Map<String, Object> input = Map.of("body", "");

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(400, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("Empty request body"));
    }

    @Test
    void should_return_400_for_null_body() {
        Map<String, Object> input = new HashMap<>();
        input.put("body", null);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(400, result.get("statusCode"));
    }

    @Test
    void should_return_400_for_missing_body() {
        Map<String, Object> input = Map.of("headers", Map.of("Content-Type", "application/json"));

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(400, result.get("statusCode"));
    }

    @Test
    void should_skip_when_no_issue_in_payload() {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "timestamp": 1234567890
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("skipped"));
        assertTrue(body.contains("No issue in payload"));
    }

    @Test
    void should_skip_when_invalid_ticket_key_format() {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "invalid-key",
                        "id": "12345",
                        "fields": {
                            "labels": ["ai-generate"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("skipped"));
        assertTrue(body.contains("Invalid ticket key format"));
    }

    @Test
    void should_skip_when_no_ai_labels_present() {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-123",
                        "id": "12345",
                        "fields": {
                            "labels": ["bug", "urgent"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("skipped"));
        assertTrue(body.contains("No AI-related labels"));
    }

    @Test
    void should_skip_duplicate_events() {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-123",
                        "id": "12345",
                        "fields": {
                            "labels": ["ai-generate"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        // Simulate duplicate - checkAndRecord returns false
        when(idempotencyService.checkAndRecord("12345", "12345")).thenReturn(false);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("skipped"));
        assertTrue(body.contains("Duplicate event"));
    }

    @Test
    void should_start_workflow_for_valid_ai_generate_label() throws Exception {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-123",
                        "id": "12345",
                        "fields": {
                            "labels": ["ai-generate"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        when(idempotencyService.checkAndRecord("12345", "12345")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("Workflow started"));
        assertTrue(body.contains("PROJ-123"));

        verify(sfnClient).startExecution(any(StartExecutionRequest.class));
        verify(ticketStateRepository).save(any());
    }

    @Test
    void should_detect_dry_run_from_ai_test_label() throws Exception {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-456",
                        "id": "67890",
                        "fields": {
                            "labels": ["ai-generate", "ai-test"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        when(idempotencyService.checkAndRecord("67890", "67890")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        handler.handleRequest(input, lambdaContext);

        // Verify dryRun=true is passed to Step Functions
        ArgumentCaptor<StartExecutionRequest> captor = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());
        var sfnInput = objectMapper.readTree(captor.getValue().input());
        assertTrue(sfnInput.get("dryRun").asBoolean());
    }

    @Test
    void should_detect_dry_run_from_dry_run_label() throws Exception {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-789",
                        "id": "11111",
                        "fields": {
                            "labels": ["ai-generate", "dry-run"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        when(idempotencyService.checkAndRecord("11111", "11111")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        handler.handleRequest(input, lambdaContext);

        ArgumentCaptor<StartExecutionRequest> captor = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());
        var sfnInput = objectMapper.readTree(captor.getValue().input());
        assertTrue(sfnInput.get("dryRun").asBoolean());
    }

    @Test
    void should_not_set_dry_run_for_ai_generate_only() throws Exception {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-100",
                        "id": "22222",
                        "fields": {
                            "labels": ["ai-generate"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        when(idempotencyService.checkAndRecord("22222", "22222")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        handler.handleRequest(input, lambdaContext);

        ArgumentCaptor<StartExecutionRequest> captor = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient).startExecution(captor.capture());
        var sfnInput = objectMapper.readTree(captor.getValue().input());
        assertFalse(sfnInput.get("dryRun").asBoolean());
    }

    @Test
    void should_handle_base64_encoded_body() throws Exception {
        String rawPayload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-200",
                        "id": "33333",
                        "fields": {
                            "labels": ["ai-generate"]
                        }
                    }
                }
                """;
        String encoded = java.util.Base64.getEncoder().encodeToString(rawPayload.getBytes());

        Map<String, Object> input = new HashMap<>();
        input.put("body", encoded);
        input.put("isBase64Encoded", true);

        when(idempotencyService.checkAndRecord("33333", "33333")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("Workflow started"));
    }

    @Test
    void should_handle_direct_invocation_format() {
        // When input IS the Jira payload directly (not wrapped in API Gateway format)
        Map<String, Object> input = new HashMap<>();
        input.put("webhookEvent", "jira:issue_updated");
        input.put("issue", Map.of(
                "key", "PROJ-300",
                "id", "44444",
                "fields", Map.of("labels", java.util.List.of("ai-generate"))));

        when(idempotencyService.checkAndRecord("44444", "44444")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
    }

    @Test
    void should_skip_when_labels_array_is_empty() {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-400",
                        "id": "55555",
                        "fields": {
                            "labels": []
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("No AI-related labels"));
    }

    @Test
    void should_return_500_for_unexpected_exceptions() {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-500",
                        "id": "66666",
                        "fields": {
                            "labels": ["ai-generate"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        when(idempotencyService.checkAndRecord("66666", "66666")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(new RuntimeException("SFN error"));

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(500, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("Internal server error"));
    }

    @Test
    void should_handle_case_insensitive_labels() throws Exception {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-600",
                        "id": "77777",
                        "fields": {
                            "labels": ["AI-GENERATE"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        when(idempotencyService.checkAndRecord("77777", "77777")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("Workflow started"));
    }

    @Test
    void should_accept_test_mode_as_valid_label() throws Exception {
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-700",
                        "id": "88888",
                        "fields": {
                            "labels": ["test-mode"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        when(idempotencyService.checkAndRecord("88888", "88888")).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("Workflow started"));
    }
}
