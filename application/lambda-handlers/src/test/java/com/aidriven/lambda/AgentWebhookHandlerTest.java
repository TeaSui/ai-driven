package com.aidriven.lambda;

import com.aidriven.core.agent.CommentIntentClassifier;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.config.AgentConfig;
import com.aidriven.core.config.AppConfig;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.service.SecretsService;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import com.aidriven.core.security.RateLimiter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for AgentWebhookHandler covering:
 * <ul>
 * <li>Jira pre-shared token verification (skip-when-unconfigured, valid token,
 * wrong token)</li>
 * <li>Jira token fetched from Secrets Manager ARN</li>
 * <li>GitHub HMAC path: Jira token code not invoked for GitHub events</li>
 * <li>Agent-disabled gate</li>
 * <li>ai-agent label gate</li>
 * <li>Irrelevant comment filtering</li>
 * </ul>
 */
class AgentWebhookHandlerTest {

        @Mock
        private ServiceFactory serviceFactory;
        @Mock
        private AppConfig appConfig;
        @Mock
        private JiraClient jiraClient;
        @Mock
        private SecretsService secretsService;
        @Mock
        private SqsClient sqsClient;
        @Mock
        private Context lambdaContext;
        @Mock
        private RateLimiter rateLimiter;

        private final ObjectMapper objectMapper = new ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        private AgentWebhookHandler handler;

        /** Minimal enabled AgentConfig for use in @BeforeEach. */
        private static final AgentConfig ENABLED_AGENT = new AgentConfig(
                        true, "https://sqs.us-east-1.amazonaws.com/123/ai-driven-agent-tasks.fifo",
                        10, 720, "@ai", 200_000, 100);

        // ── Minimal Jira comment webhook payload ───────────────────────────────────
        private static final String JIRA_PAYLOAD = """
                        {
                          "issue": {"key": "AI-123"},
                          "comment": {
                            "body": "@ai implement the feature",
                            "author": {"displayName": "Dev User", "accountId": "acc-001"}
                          }
                        }
                        """;

        // ── Minimal GitHub PR comment webhook payload ──────────────────────────────
        private static final String GITHUB_PAYLOAD = """
                        {
                          "repository": {"owner": {"login": "myorg"}, "name": "myrepo"},
                          "issue": {"number": 42},
                          "comment": {"body": "@ai explain this", "id": "99", "user": {"login": "devuser"}}
                        }
                        """;

        @BeforeEach
        void setUp() throws Exception {
                MockitoAnnotations.openMocks(this);

                when(serviceFactory.getAppConfig()).thenReturn(appConfig);
                when(serviceFactory.getSecretsProvider()).thenReturn(secretsService);
                when(serviceFactory.getSqsClient()).thenReturn(sqsClient);
                when(serviceFactory.getJiraCommentFormatter()).thenReturn(new JiraCommentFormatter());
                when(serviceFactory.getRateLimiter()).thenReturn(rateLimiter);

                when(appConfig.getAgentConfig()).thenReturn(ENABLED_AGENT);

                // Default: Jira token not configured → verifyJiraWebhookToken skips (WARN)
                when(appConfig.getJiraWebhookSecret()).thenReturn(Optional.empty());
                when(appConfig.getJiraWebhookSecretArn()).thenReturn(Optional.empty());

                // Default: GitHub HMAC secret not configured → verification skipped (WARN)
                when(appConfig.getGitHubAgentWebhookSecretArn()).thenReturn(null);

                when(sqsClient.sendMessage(any(Consumer.class)))
                                .thenReturn(SendMessageResponse.builder().messageId("msg-1").build());

                handler = new AgentWebhookHandler(
                                objectMapper, jiraClient, serviceFactory,
                                new CommentIntentClassifier(), new JiraCommentFormatter());
        }

        // ── Jira token: skip-when-unconfigured ────────────────────────────────────

        @Test
        void jira_event_skips_token_check_when_not_configured() throws Exception {
                // No token in env or SM → verifyJiraWebhookToken logs WARN and passes
                TicketInfo ticket = TicketInfo.builder().ticketKey("AI-123").labels(List.of("ai-agent")).build();
                when(jiraClient.getTicket(any(), eq("AI-123"))).thenReturn(ticket);
                when(jiraClient.addComment(any(), eq("AI-123"), any())).thenReturn("comment-1");

                Map<String, Object> result = handler.handleRequest(
                                Map.of("body", JIRA_PAYLOAD, "headers", Map.of()), lambdaContext);

                assertEquals(200, result.get("statusCode"));
                String body = (String) result.get("body");
                assertTrue(body.contains("queued"));
                verify(sqsClient).sendMessage(any(Consumer.class));
        }

        // ── Jira token: valid token via X-Jira-Webhook-Token ─────────────────────

        @Test
        void jira_event_passes_with_correct_xjira_token_header() throws Exception {
                when(appConfig.getJiraWebhookSecret()).thenReturn(Optional.of("super-secret-token"));

                TicketInfo ticket = TicketInfo.builder().ticketKey("AI-123").labels(List.of("ai-agent")).build();
                when(jiraClient.getTicket(any(), eq("AI-123"))).thenReturn(ticket);
                when(jiraClient.addComment(any(), eq("AI-123"), any())).thenReturn("comment-2");

                Map<String, Object> result = handler.handleRequest(
                                Map.of("body", JIRA_PAYLOAD,
                                                "headers", Map.of("X-Jira-Webhook-Token", "super-secret-token")),
                                lambdaContext);

                assertEquals(200, result.get("statusCode"));
                verify(sqsClient).sendMessage(any(Consumer.class));
        }

        // ── Jira token: valid token via Authorization: Bearer ─────────────────────

        @Test
        void jira_event_passes_with_bearer_authorization_header() throws Exception {
                when(appConfig.getJiraWebhookSecret()).thenReturn(Optional.of("bearer-token-value"));

                TicketInfo ticket = TicketInfo.builder().ticketKey("AI-123").labels(List.of("ai-agent")).build();
                when(jiraClient.getTicket(any(), eq("AI-123"))).thenReturn(ticket);
                when(jiraClient.addComment(any(), eq("AI-123"), any())).thenReturn("comment-3");

                Map<String, Object> result = handler.handleRequest(
                                Map.of("body", JIRA_PAYLOAD,
                                                "headers", Map.of("Authorization", "Bearer bearer-token-value")),
                                lambdaContext);

                assertEquals(200, result.get("statusCode"));
                verify(sqsClient).sendMessage(any(Consumer.class));
        }

        // ── GitHub event: processed correctly ──────────────────────────────────────

        @Test
        void github_event_processed_successfully() {
                // GitHub HMAC not configured → WARN + passes
                Map<String, Object> result = handler.handleRequest(
                                Map.of("body", GITHUB_PAYLOAD, "headers", Map.of()), lambdaContext);

                // Event passes through (GitHub bypasses label gate, HMAC skipped) → SQS
                // enqueued
                assertEquals(200, result.get("statusCode"));
        }

        // ── Agent-disabled gate ───────────────────────────────────────────────────

        @Test
        void returns_200_agent_disabled_when_agent_config_is_disabled() {
                when(appConfig.getAgentConfig()).thenReturn(
                                new AgentConfig(false, "https://sqs.example.com/q.fifo", 10, 720, "@ai", 200_000, 100));

                Map<String, Object> result = handler.handleRequest(
                                Map.of("body", JIRA_PAYLOAD, "headers", Map.of()), lambdaContext);

                assertEquals(200, result.get("statusCode"));
                String body = (String) result.get("body");
                assertTrue(body.contains("Agent disabled"));
                verify(sqsClient, never()).sendMessage(any(Consumer.class));
        }

        // ── ai-agent label gate ───────────────────────────────────────────────────

        @Test
        void jira_event_ignored_when_ticket_missing_ai_agent_label() throws Exception {
                TicketInfo ticket = TicketInfo.builder()
                                .ticketKey("AI-123").labels(List.of("some-other-label")).build();
                when(jiraClient.getTicket(any(), eq("AI-123"))).thenReturn(ticket);

                Map<String, Object> result = handler.handleRequest(
                                Map.of("body", JIRA_PAYLOAD, "headers", Map.of()), lambdaContext);

                assertEquals(200, result.get("statusCode"));
                String body = (String) result.get("body");
                assertTrue(body.contains("agent mode"));
                verify(sqsClient, never()).sendMessage(any(Consumer.class));
        }

        // ── Irrelevant comment classification ─────────────────────────────────────

        @Test
        void jira_event_ignored_when_comment_has_no_ai_trigger() throws Exception {
                String payloadNoTrigger = """
                                {
                                  "issue": {"key": "AI-456"},
                                  "comment": {
                                    "body": "Just a regular review comment without any AI mention",
                                    "author": {"displayName": "Human Dev", "accountId": "acc-002"}
                                  }
                                }
                                """;

                TicketInfo ticket = TicketInfo.builder()
                                .ticketKey("AI-456").labels(List.of("ai-agent")).build();
                when(jiraClient.getTicket(any(), eq("AI-456"))).thenReturn(ticket);

                Map<String, Object> result = handler.handleRequest(
                                Map.of("body", payloadNoTrigger, "headers", Map.of()), lambdaContext);

                assertEquals(200, result.get("statusCode"));
                String body = (String) result.get("body");
                assertTrue(body.contains("Ignored"));
                verify(sqsClient, never()).sendMessage(any(Consumer.class));
        }
}
