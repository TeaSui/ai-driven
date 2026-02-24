package com.aidriven.lambda;

import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.sfn.SfnClient;
import com.aidriven.core.config.AppConfig;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import com.aidriven.core.security.RateLimiter;

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

    @Mock
    private ServiceFactory serviceFactory;

    @Mock
    private AppConfig appConfig;

    @Mock
    private RateLimiter rateLimiter;

    private JiraWebhookHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(serviceFactory.getAppConfig()).thenReturn(appConfig);
        when(appConfig.getDefaultWorkspace()).thenReturn("test-workspace");
        when(appConfig.getDefaultRepo()).thenReturn("test-repo");
        when(appConfig.getDefaultPlatform()).thenReturn("BITBUCKET");
        when(serviceFactory.getRateLimiter()).thenReturn(rateLimiter);

        String stateMachineArn = "arn:aws:states:us-east-1:123456789:stateMachine:test";
        handler = new JiraWebhookHandler(
                objectMapper, ticketStateRepository, idempotencyService,
                jiraClient, sfnClient, stateMachineArn, serviceFactory);
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
        when(idempotencyService.checkAndRecord(anyString(), eq("12345"), eq("PROJ-123"), anyString()))
                .thenReturn(false);

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

        when(idempotencyService.checkAndRecord(anyString(), eq("12345"), eq("PROJ-123"), anyString())).thenReturn(true);
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

        when(idempotencyService.checkAndRecord(anyString(), eq("67890"), eq("PROJ-456"), anyString())).thenReturn(true);
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

        when(idempotencyService.checkAndRecord(anyString(), eq("11111"), eq("PROJ-789"), anyString())).thenReturn(true);
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

        when(idempotencyService.checkAndRecord(anyString(), eq("22222"), eq("PROJ-100"), anyString())).thenReturn(true);
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

        when(idempotencyService.checkAndRecord(anyString(), eq("33333"), eq("PROJ-200"), anyString())).thenReturn(true);
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

        when(idempotencyService.checkAndRecord(anyString(), eq("44444"), eq("PROJ-300"), anyString())).thenReturn(true);
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

        when(idempotencyService.checkAndRecord(anyString(), eq("66666"), eq("PROJ-500"), anyString())).thenReturn(true);
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

        when(idempotencyService.checkAndRecord(anyString(), eq("77777"), eq("PROJ-600"), anyString())).thenReturn(true);
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

        when(idempotencyService.checkAndRecord(anyString(), eq("88888"), eq("PROJ-700"), anyString())).thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("Workflow started"));
    }

    // ─── Exact label match security tests ───

    @Test
    void should_skip_when_label_is_prefixed_variant_of_ai_generate() {
        // "custom-ai-generate-label" CONTAINS "ai-generate" as substring but must NOT
        // trigger
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-800",
                        "id": "90001",
                        "fields": {
                            "labels": ["custom-ai-generate-label"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("skipped"),
                "custom-ai-generate-label must not trigger the pipeline; got: " + body);
        assertTrue(body.contains("No AI-related labels"),
                "Reason should indicate no valid AI labels; got: " + body);
        // Idempotency should NOT be checked — handler skipped before that point
        verify(idempotencyService, never()).checkAndRecord(any(), any(), any(), any());
    }

    @Test
    void should_skip_when_label_is_suffixed_variant_of_ai_generate() {
        // "ai-generate-custom" has the prefix "ai-generate" but must NOT trigger
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-801",
                        "id": "90002",
                        "fields": {
                            "labels": ["ai-generate-custom"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        String body = (String) result.get("body");
        assertTrue(body.contains("skipped"),
                "ai-generate-custom must not trigger the pipeline; got: " + body);
        verify(idempotencyService, never()).checkAndRecord(any(), any(), any(), any());
    }

    @Test
    void should_skip_when_label_only_partially_matches_ai_test() {
        // "ai-testing" is NOT in VALID_AI_LABELS (which contains "ai-test")
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-802",
                        "id": "90003",
                        "fields": {
                            "labels": ["ai-testing"]
                        }
                    }
                }
                """;
        Map<String, Object> input = Map.of("body", payload);

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"));
        assertTrue(((String) result.get("body")).contains("skipped"));
    }

    // ─── HMAC skip when no secret configured ───

    @Test
    void should_process_webhook_normally_when_no_jira_webhook_secret_configured() throws Exception {
        // Default mock: appConfig.getJiraWebhookSecret() → Optional.empty()
        // appConfig.getJiraWebhookSecretArn() → Optional.empty()
        // → resolveJiraWebhookSecret() returns null → verifyJiraWebhookToken skips with
        // WARN
        String payload = """
                {
                    "webhookEvent": "jira:issue_updated",
                    "issue": {
                        "key": "PROJ-803",
                        "id": "90004",
                        "fields": {
                            "labels": ["ai-generate"]
                        }
                    }
                }
                """;
        // No extra headers — HMAC verification is skipped when secret is absent
        Map<String, Object> input = Map.of("body", payload);

        when(idempotencyService.checkAndRecord(anyString(), eq("90004"), eq("PROJ-803"), anyString()))
                .thenReturn(true);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().build());

        Map<String, Object> result = handler.handleRequest(input, lambdaContext);

        assertEquals(200, result.get("statusCode"),
                "Webhook should be accepted when no JIRA_WEBHOOK_SECRET is configured");
        String body = (String) result.get("body");
        assertTrue(body.contains("Workflow started"));
        verify(sfnClient).startExecution(any(StartExecutionRequest.class));
    }
}
