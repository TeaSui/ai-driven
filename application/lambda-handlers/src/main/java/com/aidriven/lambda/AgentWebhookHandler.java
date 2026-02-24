package com.aidriven.lambda;

import com.aidriven.core.agent.CommentIntentClassifier;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.aidriven.lambda.model.AgentTask;
import com.aidriven.lambda.model.GithubEvent;
import com.aidriven.lambda.model.JiraEvent;
import com.aidriven.lambda.model.WebhookEvent;
import com.aidriven.lambda.parser.WebhookParser;
import com.aidriven.lambda.security.JiraWebhookSecretResolver;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.aidriven.lambda.security.WebhookValidator;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * Lambda handler for agent mode webhook events.
 * Responsible for validating the webhook event, classifying intents,
 * logging acknowledgements, and delegating the heavy work to SQS.
 */
@Slf4j
public class AgentWebhookHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final ObjectMapper objectMapper;
    private final JiraClient jiraClient;
    private final ServiceFactory serviceFactory;
    private final CommentIntentClassifier classifier;
    private final JiraCommentFormatter formatter;
    private final JiraWebhookSecretResolver jiraSecretResolver;

    /** No-arg constructor required by AWS Lambda runtime. */
    public AgentWebhookHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.objectMapper = factory.getObjectMapper();
        this.jiraClient = factory.getJiraClient();
        this.serviceFactory = factory;
        this.classifier = new CommentIntentClassifier();
        this.formatter = factory.getJiraCommentFormatter();
        this.jiraSecretResolver = new JiraWebhookSecretResolver(
                factory.getAppConfig(), factory.getSecretsProvider());
    }

    /** Constructor for testing. */
    public AgentWebhookHandler(ObjectMapper objectMapper, JiraClient jiraClient,
            ServiceFactory serviceFactory,
            CommentIntentClassifier classifier,
            JiraCommentFormatter formatter) {
        this.objectMapper = objectMapper;
        this.jiraClient = jiraClient;
        this.serviceFactory = serviceFactory;
        this.classifier = classifier;
        this.formatter = formatter;
        this.jiraSecretResolver = new JiraWebhookSecretResolver(
                serviceFactory.getAppConfig(), serviceFactory.getSecretsProvider());
    }

    private void checkRateLimits(WebhookEvent event, com.aidriven.core.security.RateLimiter rateLimiter,
            com.aidriven.core.config.AppConfig config) {
        String ticketKey = event.getTicketKey();
        String author = event.getCommentAuthorAccountId() != null ? event.getCommentAuthorAccountId()
                : event.getCommentAuthor();

        // 1. Per-ticket limit
        rateLimiter.consumeOrThrow("ticket:" + ticketKey, config.getMaxRequestsPerTicketPerHour());

        // 2. Per-user limit (across tickets)
        if (author != null && !author.isBlank()) {
            rateLimiter.consumeOrThrow("user:" + author, config.getMaxRequestsPerUserPerHour());
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        try {
            log.info("Agent webhook received event");

            String body = extractBody(input);
            Map<String, String> headers = (Map<String, String>) input.getOrDefault("headers", Map.of());

            String correlationId = headers.getOrDefault("x-correlation-id",
                    headers.getOrDefault("X-Correlation-Id", UUID.randomUUID().toString()));
            MDC.put("correlationId", correlationId);

            JsonNode payload = objectMapper.readTree(body);

            WebhookEvent event = WebhookParser.parse(payload);

            if (event instanceof GithubEvent ghEvent && "CI_FAILURE".equals(ghEvent.getGithubCommentType())) {
                String action = payload.path("action").asText();
                String conclusion = payload.path("workflow_run").path("conclusion").asText();
                if (!"completed".equals(action) || !"failure".equals(conclusion)) {
                    log.info("Ignoring CI run because action={} and conclusion={}", action, conclusion);
                    return response(200, "Ignored - not a failed complete run");
                }
            }

            if (event.getTicketKey() == null || event.getCommentBody() == null) {
                log.warn("Missing ticket key or comment body in webhook");
                return response(400, "Missing required fields");
            }

            WebhookValidator.validateTicketKey(event.getTicketKey());

            if (event instanceof GithubEvent ghEvent) {
                WebhookValidator.validateRepoOwner(ghEvent.getRepoOwner());
                WebhookValidator.validateRepoSlug(ghEvent.getRepoSlug());

                // Verify X-Hub-Signature-256 using the dedicated agent webhook HMAC secret.
                // Separate from GitHub API credentials (GITHUB_SECRET_ARN); fetched as a raw
                // string.
                String webhookSecretArn = serviceFactory.getAppConfig().getGitHubAgentWebhookSecretArn();
                if (webhookSecretArn != null && !webhookSecretArn.isBlank()) {
                    String webhookSecret = serviceFactory.getSecretsProvider().getSecret(webhookSecretArn);
                    WebhookValidator.verifyGithubSignature(headers, body, webhookSecret);
                } else {
                    log.warn("GITHUB_AGENT_WEBHOOK_SECRET_ARN not configured; GitHub signature verification skipped");
                }
            }

            if (!serviceFactory.getAppConfig().getAgentConfig().enabled()) {
                log.info("Agent is disabled, ignoring event for {}", event.getTicketKey());
                return response(200, "Agent disabled");
            }

            // Jira events: verify pre-shared token, then check ai-agent label
            if (event instanceof JiraEvent) {
                WebhookValidator.verifyJiraWebhookToken(headers, jiraSecretResolver.resolve());
                if (!hasAgentLabel(event.getTicketKey(), event.getOperationContext())) {
                    log.info("Ticket {} does not have ai-agent label, ignoring comment", event.getTicketKey());
                    return response(200, "Ticket not in agent mode \u2014 add 'ai-agent' label to enable");
                }
            }

            boolean isBot = isFromBot(event);
            CommentIntent intent = classifier.classify(event.getCommentBody(), isBot);

            if (intent == CommentIntent.IRRELEVANT) {
                log.info("Ignoring irrelevant comment on {}", event.getTicketKey());
                return response(200, "Ignored — not an agent command");
            }

            // Enforce Rate Limits only for agent commands
            checkRateLimits(event, serviceFactory.getRateLimiter(), serviceFactory.getAppConfig());

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

            String queueUrl = serviceFactory.getAppConfig().getAgentConfig().queueUrl();
            if (queueUrl == null || queueUrl.isBlank()) {
                throw new IllegalStateException("AGENT_QUEUE_URL is not configured");
            }

            enqueueEvent(event, userMessage, event.getCommentBody(), ackCommentId, queueUrl, correlationId);

            return response(200, "Agent task queued");

        } catch (com.aidriven.core.security.RateLimitExceededException e) {
            log.warn("Rate limit exceeded: {}", e.getMessage());
            return response(429, e.getMessage());
        } catch (SecurityException e) {
            log.warn("Webhook security validation failed: {}", e.getMessage());
            return response(400, "Unauthorized: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Validation failed: {}", e.getMessage());
            return response(400, "Validation failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("Agent webhook error: {}", e.getMessage(), e);
            return response(500, "Internal error: " + e.getMessage());
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void enqueueEvent(WebhookEvent event, String userMessage, String rawBody, String ackCommentId,
            String queueUrl, String correlationId) throws Exception {

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
            // Capture parent comment info for reply formatting
            // Use the stripped message as the excerpt (what the user actually asked)
            taskBuilder
                    .parentCommentExcerpt(WebhookValidator.sanitizeCommentBody(userMessage))
                    .parentCommentAuthorAccountId(event.getCommentAuthorAccountId());
        }

        AgentTask task = taskBuilder.build();
        String messageBody = objectMapper.writeValueAsString(task);

        serviceFactory.getSqsClient().sendMessage(req -> req
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .messageGroupId(task.getTicketKey())
                .messageDeduplicationId(UUID.randomUUID().toString()));

        log.info("Enqueued task for ticket={} to queue={}", task.getTicketKey(), queueUrl);
    }

    private String extractBody(Map<String, Object> input) {
        if (input.containsKey("body")) {
            return (String) input.get("body");
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> response(int statusCode, String message) {
        try {
            return Map.of(
                    "statusCode", statusCode,
                    "headers", Map.of("Content-Type", "application/json"),
                    "body", objectMapper.writeValueAsString(Map.of("message", message)));
        } catch (Exception e) {
            log.error("Failed to format response", e);
            return Map.of("statusCode", 500, "body", "{\"message\":\"Internal error formatting response\"}");
        }
    }

    /**
     * Determines if the event was authored by the configured bot service account.
     *
     * <p>
     * Uses a multi-layered approach for self-loop prevention:
     * 1. AccountId matching (immutable, most reliable)
     * 2. Display name matching (fragile, backward-compatible)
     * 3. Comment content signature detection (catches missed cases)
     */
    private boolean isFromBot(WebhookEvent event) {
        String configuredAccountId = serviceFactory.getAppConfig().getAgentConfig().botAccountId();
        String eventAccountId = event.getCommentAuthorAccountId();

        // Layer 1: Immutable accountId match — most reliable
        if (configuredAccountId != null && !configuredAccountId.isBlank()) {
            if (configuredAccountId.equals(eventAccountId)) {
                return true;
            }
        }

        // Layer 2: Display name match (backward-compatible, but fragile)
        String author = event.getCommentAuthor();
        if ("AI Agent".equalsIgnoreCase(author) || "ai-agent".equalsIgnoreCase(author)) {
            return true;
        }

        // Layer 3: Comment content signature detection
        // This catches cases where the bot posts under a different identity
        String body = event.getCommentBody();
        if (body != null) {
            String lowerBody = body.toLowerCase();
            if (lowerBody.contains("🤖 processing your request") ||
                    lowerBody.contains("working on it — this comment will be updated") ||
                    lowerBody.contains("_🤖 ai agent") ||
                    lowerBody.contains("| tools:") ||
                    lowerBody.contains("🤖 ❌ *error processing request*")) {
                log.info("Detected bot signature in comment body, treating as bot");
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether the Jira ticket has the {@code ai-agent} label.
     * This is the opt-in gate: only labeled tickets receive agent responses.
     */
    private boolean hasAgentLabel(String ticketKey, com.aidriven.spi.model.OperationContext ctx) {
        try {
            com.aidriven.core.model.TicketInfo ticket = jiraClient.getTicket(ctx, ticketKey);
            return ticket != null
                    && ticket.getLabels() != null
                    && ticket.getLabels().contains("ai-agent");
        } catch (Exception e) {
            log.warn("Failed to fetch ticket {} for label check, defaulting to deny: {}", ticketKey, e.getMessage());
            return false;
        }
    }

}
