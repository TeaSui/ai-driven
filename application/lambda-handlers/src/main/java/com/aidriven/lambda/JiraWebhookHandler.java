package com.aidriven.lambda;

import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.model.TicketKey;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.IdempotencyService;

import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aidriven.lambda.security.JiraWebhookSecretResolver;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.Base64;

import com.aidriven.lambda.factory.ServiceFactory;
import com.aidriven.lambda.security.WebhookValidator;

/**
 * Lambda handler for direct API Gateway → Lambda integration for Jira webhooks.
 */
@Slf4j
public class JiraWebhookHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]+-\\d+$");
    private static final List<String> VALID_AI_LABELS = List.of("ai-generate", "ai-test", "dry-run", "test-mode");

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final IdempotencyService idempotencyService;
    private final JiraClient jiraClient;
    private final SfnClient sfnClient;
    private final String stateMachineArn;
    private final ServiceFactory serviceFactory;
    private final JiraWebhookSecretResolver webhookSecretResolver;

    /** No-arg constructor required by AWS Lambda runtime. */
    public JiraWebhookHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.serviceFactory = factory;
        this.objectMapper = factory.getObjectMapper();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.idempotencyService = factory.getIdempotencyService();
        this.jiraClient = factory.getJiraClient();
        this.sfnClient = factory.getSfnClient();
        this.stateMachineArn = factory.getAppConfig().getStateMachineArn().orElse("");
        this.webhookSecretResolver = new JiraWebhookSecretResolver(
                factory.getAppConfig(), factory.getSecretsProvider());
    }

    public JiraWebhookHandler(ObjectMapper objectMapper, TicketStateRepository ticketStateRepository,
            IdempotencyService idempotencyService, JiraClient jiraClient, SfnClient sfnClient,
            String stateMachineArn, ServiceFactory serviceFactory) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.idempotencyService = idempotencyService;
        this.jiraClient = jiraClient;
        this.sfnClient = sfnClient;
        this.stateMachineArn = stateMachineArn;
        this.serviceFactory = serviceFactory;
        this.webhookSecretResolver = new JiraWebhookSecretResolver(
                serviceFactory.getAppConfig(), serviceFactory.getSecretsProvider());
    }

    @Override
    @Logging(logEvent = true)
    @Tracing
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        LoggingUtils.appendKey("correlationId", context.getAwsRequestId());
        log.info("JiraWebhookHandler invoked");

        try {
            String body = extractBody(input);
            if (body == null || body.isBlank()) {
                log.warn("Empty request body");
                return createResponse(400, Map.of("error", "Empty request body"));
            }

            // Verify Jira webhook pre-shared token (skipped if not configured)
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) input.getOrDefault("headers", Map.of());
            WebhookValidator.verifyJiraWebhookToken(headers, webhookSecretResolver.resolve());

            JsonNode payload = objectMapper.readTree(body);

            ProcessResult result = processWebhook(input, payload);

            if (result.skipped()) {
                log.info("Skipped webhook processing: {}", result.reason());
                return createResponse(200, Map.of(
                        "message", "Webhook processed but skipped",
                        "reason", result.reason()));
            }

            return createResponse(200, Map.of(
                    "message", "Workflow started",
                    "ticketKey", result.ticketKey(),
                    "executionName", result.executionName()));

        } catch (com.aidriven.core.security.RateLimitExceededException e) {
            log.warn("Rate limit exceeded: {}", e.getMessage());
            return createResponse(429, Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            log.warn("Webhook security validation failed: {}", e.getMessage());
            return createResponse(400, Map.of("error", "Unauthorized: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return createResponse(400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process webhook", e);
            return createResponse(500, Map.of("error", "Internal server error"));
        } finally {
            // Context cleared by Powertools
        }
    }

    private String extractBody(Map<String, Object> input) {
        if (input.containsKey("body")) {
            Object body = input.get("body");
            if (body instanceof String) {
                if (Boolean.TRUE.equals(input.get("isBase64Encoded"))) {
                    return new String(Base64.getDecoder().decode((String) body),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                return (String) body;
            }
        }
        if (input.containsKey("issue") || input.containsKey("webhookEvent")) {
            try {
                return objectMapper.writeValueAsString(input);
            } catch (Exception e) {
                log.warn("Serialization failed", e);
            }
        }
        return null;
    }

    private ProcessResult processWebhook(Map<String, Object> input, JsonNode payload) throws Exception {
        JsonNode issue = payload.get("issue");
        if (issue == null)
            return ProcessResult.skipped("No issue in payload");

        String ticketKey = issue.path("key").asText();
        String ticketId = issue.path("id").asText();
        LoggingUtils.appendKey("ticketKey", ticketKey);
        // In Powertools we can use LoggingUtils.appendKey or just log it
        log.info("Processing webhook for ticket: {}", ticketKey);

        if (!TICKET_KEY_PATTERN.matcher(ticketKey).matches()) {
            return ProcessResult.skipped("Invalid ticket key format: " + ticketKey);
        }

        List<String> labels = extractLabels(issue);
        if (!hasValidAiLabel(labels))
            return ProcessResult.skipped("No AI-related labels found");

        OperationContext context = extractContext(payload);

        // Enforce Rate Limits
        checkRateLimits(ticketKey, context.getUserId().orElse(null), serviceFactory.getRateLimiter(),
                serviceFactory.getAppConfig());

        // Defensive checks for non-null requirements in TicketState
        if (ticketKey == null || ticketKey.isBlank()) {
            log.warn("Missing ticketKey in payload, using UNKNOWN");
            ticketKey = "UNKNOWN";
        }
        if (ticketId == null || ticketId.isBlank()) {
            log.warn("Missing ticketId in payload, using UNKNOWN");
            ticketId = "UNKNOWN";
        }

        // Create a unique event ID for idempotency
        String eventId = extractEventId(input, payload, ticketId, labels);
        log.info("Checking idempotency for ticket {} with eventId: {}", ticketKey, eventId);
        if (!idempotencyService.checkAndRecord(context.tenantId(), ticketId, ticketKey, eventId)) {
            return ProcessResult.skipped("Duplicate event - already processed (eventId=" + eventId + ")");
        }

        ticketStateRepository.save(
                TicketState.forTicket(context.tenantId(), ticketId, ticketKey, ProcessingStatus.RECEIVED));
        transitionToInProgress(context, ticketKey);

        // Resolve repo metadata from labels (Phase G)
        com.aidriven.core.source.RepositoryResolver.ResolvedRepository repo = com.aidriven.core.source.RepositoryResolver
                .resolve(
                        labels, null,
                        serviceFactory.getAppConfig().getDefaultWorkspace(),
                        serviceFactory.getAppConfig().getDefaultRepo(),
                        serviceFactory.getAppConfig().getDefaultPlatform());

        String execName = ticketKey + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> sfnInput = new HashMap<>();
        sfnInput.put("ticketId", ticketId);
        sfnInput.put("ticketKey", ticketKey);
        sfnInput.put("webhookEvent", payload.path("webhookEvent").asText());
        sfnInput.put("dryRun", hasDryRunLabel(labels));
        sfnInput.put("labels", labels);
        sfnInput.put("platform",
                repo != null ? repo.platform().name() : serviceFactory.getAppConfig().getDefaultPlatform());
        sfnInput.put("repoOwner", repo != null ? repo.owner() : "");
        sfnInput.put("repoSlug", repo != null ? repo.repo() : "");
        sfnInput.put("context", Map.of(
                "tenantId", context.tenantId(),
                "userId", context.getUserId().orElse("system"),
                "ticketKey", ticketKey,
                "correlationId", context.getCorrelationId()));

        sfnClient.startExecution(StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(execName)
                .input(objectMapper.writeValueAsString(sfnInput))
                .build());

        log.info("Started Step Functions execution: {} for ticket {}", execName, ticketKey);
        return ProcessResult.success(ticketKey, execName);
    }

    private List<String> extractLabels(JsonNode issue) {
        List<String> labels = new ArrayList<>();
        JsonNode node = issue.path("fields").path("labels");
        if (node.isArray()) {
            for (JsonNode l : node)
                labels.add(l.asText().toLowerCase());
        }
        return labels;
    }

    private boolean hasValidAiLabel(List<String> labels) {
        // Exact match only — prevents "custom-ai-generate-label" from triggering the
        // pipeline
        return labels.stream().anyMatch(VALID_AI_LABELS::contains);
    }

    private boolean hasDryRunLabel(List<String> labels) {
        return labels.stream().anyMatch(l -> l.contains("ai-test") || l.contains("dry-run") || l.contains("test-mode"));
    }

    private void transitionToInProgress(OperationContext context, String ticketKey) {
        try {
            jiraClient.updateStatus(context, ticketKey, "In Progress");
        } catch (Exception e) {
            log.info("Transition to In Progress failed/skipped: {}", e.getMessage());
        }
    }

    private void checkRateLimits(String ticketKey, String userId, com.aidriven.core.security.RateLimiter rateLimiter,
            com.aidriven.core.config.AppConfig config) {
        // 1. Per-ticket limit
        rateLimiter.consumeOrThrow("ticket:" + ticketKey, config.getMaxRequestsPerTicketPerHour());

        // 2. Per-user limit (across tickets)
        if (userId != null && !userId.isBlank() && !userId.equals("system")) {
            rateLimiter.consumeOrThrow("user:" + userId, config.getMaxRequestsPerUserPerHour());
        }
    }

    private Map<String, Object> createResponse(int status, Map<String, Object> body) {
        try {
            // No CORS header: this is an internal webhook endpoint, not a browser-facing
            // API.
            return Map.of("statusCode", status,
                    "headers", Map.of("Content-Type", "application/json"),
                    "body", objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            return Map.of("statusCode", 500, "body", "{\"error\":\"Internal Failure\"}");
        }
    }

    /**
     * Extracts a unique event ID for idempotency checking.
     *
     * Uses a time-windowed approach to prevent concurrent webhook triggers.
     * Jira often sends multiple webhook deliveries for the same logical event
     * (each with a unique delivery ID), so we deduplicate by:
     * ticket + trigger labels + 10-second time window
     *
     * This ensures that rapid-fire webhooks for the same ticket+labels
     * within 10 seconds are treated as duplicates.
     */
    private String extractEventId(Map<String, Object> input, JsonNode payload, String ticketId, List<String> labels) {
        // Time-windowed deduplication (10-second windows)
        // This handles Jira sending multiple deliveries for the same label add event
        long windowSeconds = Instant.now().getEpochSecond() / 10;

        // Only include trigger labels (ai-generate, ai-agent) in the key
        // This prevents different label combinations from being deduplicated together
        List<String> triggerLabels = labels.stream()
                .filter(l -> l.startsWith("ai-"))
                .sorted()
                .toList();

        return "trigger-" + ticketId + "-" + String.join(",", triggerLabels) + "-w" + windowSeconds;
    }

    private record ProcessResult(boolean skipped, String reason, String ticketKey, String executionName) {
        static ProcessResult skipped(String r) {
            return new ProcessResult(true, r, null, null);
        }

        static ProcessResult success(String k, String e) {
            return new ProcessResult(false, null, k, e);
        }
    }

    private OperationContext extractContext(JsonNode payload) {
        String tenantId = "default";
        if (payload.has("baseUrl")) {
            tenantId = payload.get("baseUrl").asText().replaceAll("https?://", "").replaceAll("\\.atlassian\\.net.*",
                    "");
        }
        String userId = "system";
        // User info might be in 'user' field in some Jira webhooks
        if (payload.has("user")) {
            userId = payload.get("user").path("accountId").asText("system");
        }

        String ticketKey = payload.path("issue").path("key").asText();

        return OperationContext.builder()
                .tenantId(tenantId)
                .userId(userId)
                .ticketKey(TicketKey.of(ticketKey))
                .build();
    }
}
