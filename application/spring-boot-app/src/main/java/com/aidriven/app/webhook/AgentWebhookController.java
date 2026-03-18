package com.aidriven.app.webhook;

import com.aidriven.app.config.AppProperties;
import com.aidriven.core.agent.CommentIntentClassifier;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.security.RateLimitExceededException;
import com.aidriven.core.security.RateLimiter;
import com.aidriven.core.service.SecretsService;
import com.aidriven.jira.JiraClient;
import com.aidriven.core.model.AgentTask;
import com.aidriven.core.model.GithubEvent;
import com.aidriven.core.model.JiraEvent;
import com.aidriven.core.model.WebhookEvent;
import com.aidriven.core.webhook.WebhookParser;
import com.aidriven.core.security.JiraWebhookSecretResolver;
import com.aidriven.core.security.WebhookValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller replacing AgentWebhookHandler Lambda.
 * Handles agent-mode webhook events from both Jira and GitHub,
 * classifies intent, and enqueues tasks to SQS for async processing.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks")
public class AgentWebhookController {

    private final ObjectMapper objectMapper;
    private final JiraClient jiraClient;
    private final CommentIntentClassifier classifier;
    private final JiraCommentFormatter formatter;
    private final JiraWebhookSecretResolver jiraSecretResolver;
    private final SqsClient sqsClient;
    private final AppProperties appProperties;
    private final SecretsService secretsService;
    private final RateLimiter rateLimiter;
    private final String agentQueueUrl;

    public AgentWebhookController(
            ObjectMapper objectMapper,
            JiraClient jiraClient,
            CommentIntentClassifier classifier,
            JiraCommentFormatter formatter,
            JiraWebhookSecretResolver jiraSecretResolver,
            SqsClient sqsClient,
            SecretsService secretsService,
            RateLimiter rateLimiter,
            AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.jiraClient = jiraClient;
        this.classifier = classifier;
        this.formatter = formatter;
        this.jiraSecretResolver = jiraSecretResolver;
        this.sqsClient = sqsClient;
        this.appProperties = appProperties;
        this.secretsService = secretsService;
        this.rateLimiter = rateLimiter;
        this.agentQueueUrl = appProperties.aws().sqs().agentQueueUrl();
    }

    /**
     * POST /webhooks/jira/agent - Replaces AgentWebhookHandler for Jira events.
     * Validates Jira token, classifies intent, and enqueues to SQS.
     */
    @PostMapping("/jira/agent")
    public ResponseEntity<Map<String, String>> handleJiraAgent(
            @RequestBody String body,
            @RequestHeader Map<String, String> headers) {

        return handleAgentWebhook(body, headers, "JIRA");
    }

    /**
     * POST /webhooks/github/agent - Replaces AgentWebhookHandler for GitHub events.
     * Validates HMAC signature, classifies intent, and enqueues to SQS.
     */
    @PostMapping("/github/agent")
    public ResponseEntity<Map<String, String>> handleGitHubAgent(
            @RequestBody String body,
            @RequestHeader Map<String, String> headers) {

        return handleAgentWebhook(body, headers, "GITHUB");
    }

    private ResponseEntity<Map<String, String>> handleAgentWebhook(
            String body, Map<String, String> headers, String expectedPlatform) {

        String correlationId = headers.getOrDefault("x-correlation-id",
                headers.getOrDefault("X-Correlation-Id", UUID.randomUUID().toString()));
        MDC.put("correlationId", correlationId);

        try {
            log.info("Agent webhook received event (expected platform={})", expectedPlatform);

            JsonNode payload = objectMapper.readTree(body);
            WebhookEvent event = WebhookParser.parse(payload);

            // GitHub CI failure filter
            if (event instanceof GithubEvent ghEvent && "CI_FAILURE".equals(ghEvent.getGithubCommentType())) {
                String action = payload.path("action").asText();
                String conclusion = payload.path("workflow_run").path("conclusion").asText();
                if (!"completed".equals(action) || !"failure".equals(conclusion)) {
                    log.info("Ignoring CI run because action={} and conclusion={}", action, conclusion);
                    return ResponseEntity.ok(Map.of("message", "Ignored - not a failed complete run"));
                }
            }

            if (event.getTicketKey() == null || event.getCommentBody() == null) {
                log.warn("Missing ticket key or comment body in webhook");
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Missing required fields"));
            }

            WebhookValidator.validateTicketKey(event.getTicketKey());

            // Platform-specific validation
            if (event instanceof GithubEvent ghEvent) {
                WebhookValidator.validateRepoOwner(ghEvent.getRepoOwner());
                WebhookValidator.validateRepoSlug(ghEvent.getRepoSlug());

                String webhookSecretArn = appProperties.github().webhookSecretArn();
                if (webhookSecretArn != null && !webhookSecretArn.isBlank()) {
                    String webhookSecret = secretsService.getSecret(webhookSecretArn);
                    WebhookValidator.verifyGithubSignature(headers, body, webhookSecret);
                } else {
                    log.warn("GITHUB_AGENT_WEBHOOK_SECRET_ARN not configured; GitHub signature verification skipped");
                }
            }

            if (!appProperties.agent().enabled()) {
                log.info("Agent is disabled, ignoring event for {}", event.getTicketKey());
                return ResponseEntity.ok(Map.of("message", "Agent disabled"));
            }

            // Jira events: verify pre-shared token, then check ai-agent label
            if (event instanceof JiraEvent) {
                WebhookValidator.verifyJiraWebhookToken(headers, jiraSecretResolver.resolve());
                if (!hasAgentLabel(event.getTicketKey(), event.getOperationContext())) {
                    log.info("Ticket {} does not have ai-agent label, ignoring comment", event.getTicketKey());
                    return ResponseEntity.ok(
                            Map.of("message", "Ticket not in agent mode - add 'ai-agent' label to enable"));
                }
            }

            boolean isBot = isFromBot(event);
            CommentIntent intent = classifier.classify(event.getCommentBody(), isBot);

            if (intent == CommentIntent.IRRELEVANT) {
                log.info("Ignoring irrelevant comment on {}", event.getTicketKey());
                return ResponseEntity.ok(Map.of("message", "Ignored - not an agent command"));
            }

            checkRateLimits(event);

            log.info("Processing {} intent for ticket={} by={}", intent, event.getTicketKey(),
                    event.getCommentAuthor());

            String userMessage = classifier.stripMention(event.getCommentBody());
            String ackCommentId = null;

            if (event instanceof GithubEvent ghEvent) {
                ackCommentId = ghEvent.getEventIdempotencyKey();
                log.info("Detected GitHub PR comment for {}", ghEvent.getTicketKey());
            } else if (event instanceof JiraEvent) {
                String ackComment = formatter.formatAck(userMessage, event.getCommentAuthorAccountId());
                ackCommentId = jiraClient.addComment(event.getOperationContext(), event.getTicketKey(), ackComment);
            }

            if (agentQueueUrl == null || agentQueueUrl.isBlank()) {
                throw new IllegalStateException("AGENT_QUEUE_URL is not configured");
            }

            enqueueEvent(event, userMessage, event.getCommentBody(), ackCommentId, correlationId);

            return ResponseEntity.ok(Map.of("message", "Agent task queued"));

        } catch (RateLimitExceededException e) {
            log.warn("Rate limit exceeded: {}", e.getMessage());
            return ResponseEntity.status(429)
                    .body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            log.warn("Webhook security validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Unauthorized: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("Validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Validation failed: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Agent webhook error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Internal error"));
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void enqueueEvent(WebhookEvent event, String userMessage, String rawBody,
                              String ackCommentId, String correlationId) throws Exception {

        AgentTask.AgentTaskBuilder taskBuilder = AgentTask.builder()
                .ticketKey(event.getTicketKey())
                .commentBody(WebhookValidator.sanitizeCommentBody(userMessage))
                .rawCommentBody(WebhookValidator.sanitizeCommentBody(rawBody))
                .commentAuthor(WebhookValidator.sanitizeAuthor(event.getCommentAuthor()))
                .commentAuthorAccountId(event.getCommentAuthorAccountId())
                .platform(event.getPlatform())
                .context(event.getOperationContext())
                .ackCommentId(ackCommentId)
                .correlationId(correlationId);

        if (event instanceof GithubEvent ghEvent) {
            taskBuilder
                    .repoOwner(ghEvent.getRepoOwner())
                    .repoSlug(ghEvent.getRepoSlug())
                    .prNumber(ghEvent.getPrNumber())
                    .githubCommentId(ghEvent.getGithubCommentId())
                    .githubCommentType(ghEvent.getGithubCommentType())
                    .diffHunk(WebhookValidator.sanitizeDiffHunk(ghEvent.getDiffHunk()))
                    .filePath(ghEvent.getFilePath())
                    .commitId(ghEvent.getCommitId());
        } else if (event instanceof JiraEvent) {
            taskBuilder
                    .parentCommentExcerpt(WebhookValidator.sanitizeCommentBody(userMessage))
                    .parentCommentAuthorAccountId(event.getCommentAuthorAccountId());
        }

        AgentTask task = taskBuilder.build();
        String messageBody = objectMapper.writeValueAsString(task);

        sqsClient.sendMessage(req -> req
                .queueUrl(agentQueueUrl)
                .messageBody(messageBody)
                .messageGroupId(task.getTicketKey())
                .messageDeduplicationId(UUID.randomUUID().toString()));

        log.info("Enqueued task for ticket={} to queue", task.getTicketKey());
    }

    private void checkRateLimits(WebhookEvent event) {
        String ticketKey = event.getTicketKey();
        String author = event.getCommentAuthorAccountId() != null
                ? event.getCommentAuthorAccountId()
                : event.getCommentAuthor();

        rateLimiter.consumeOrThrow("ticket:" + ticketKey, appProperties.cost().maxRequestsPerTicketPerHour());

        if (author != null && !author.isBlank()) {
            rateLimiter.consumeOrThrow("user:" + author, appProperties.cost().maxRequestsPerUserPerHour());
        }
    }

    private boolean isFromBot(WebhookEvent event) {
        String configuredAccountId = appProperties.agent().botAccountId();
        String eventAccountId = event.getCommentAuthorAccountId();

        if (configuredAccountId != null && !configuredAccountId.isBlank()) {
            if (configuredAccountId.equals(eventAccountId)) {
                return true;
            }
        }

        String author = event.getCommentAuthor();
        if ("AI Agent".equalsIgnoreCase(author) || "ai-agent".equalsIgnoreCase(author)) {
            return true;
        }

        String commentBody = event.getCommentBody();
        if (commentBody != null) {
            String lowerBody = commentBody.toLowerCase();
            if (lowerBody.contains("processing your request")
                    || lowerBody.contains("working on it")
                    || lowerBody.contains("ai agent")
                    || lowerBody.contains("| tools:")
                    || lowerBody.contains("error processing request")) {
                log.info("Detected bot signature in comment body, treating as bot");
                return true;
            }
        }

        return false;
    }

    private boolean hasAgentLabel(String ticketKey, com.aidriven.spi.model.OperationContext ctx) {
        try {
            TicketInfo ticket = jiraClient.getTicket(ctx, ticketKey);
            return ticket != null
                    && ticket.getLabels() != null
                    && ticket.getLabels().contains("ai-agent");
        } catch (Exception e) {
            log.warn("Failed to fetch ticket {} for label check, defaulting to deny: {}", ticketKey, e.getMessage());
            return false;
        }
    }
}
