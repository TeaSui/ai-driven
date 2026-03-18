package com.aidriven.app.webhook;

import com.aidriven.app.config.AppProperties;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.security.RateLimitExceededException;
import com.aidriven.core.security.RateLimiter;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.source.RepositoryResolver;
import com.aidriven.jira.JiraClient;
import com.aidriven.core.security.WebhookValidator;
import com.aidriven.core.security.JiraWebhookSecretResolver;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.model.TicketKey;
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
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * REST controller replacing JiraWebhookHandler Lambda.
 * Handles Jira webhook events that trigger the pipeline Step Functions workflow.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/jira")
public class JiraWebhookController {

    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]+-\\d+$");
    private static final List<String> VALID_AI_LABELS = List.of("ai-generate", "ai-test", "dry-run", "test-mode");

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final IdempotencyService idempotencyService;
    private final JiraClient jiraClient;
    private final SfnClient sfnClient;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;
    private final JiraWebhookSecretResolver webhookSecretResolver;
    private final String stateMachineArn;

    public JiraWebhookController(
            ObjectMapper objectMapper,
            TicketStateRepository ticketStateRepository,
            IdempotencyService idempotencyService,
            JiraClient jiraClient,
            SfnClient sfnClient,
            RateLimiter rateLimiter,
            JiraWebhookSecretResolver webhookSecretResolver,
            AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.idempotencyService = idempotencyService;
        this.jiraClient = jiraClient;
        this.sfnClient = sfnClient;
        this.rateLimiter = rateLimiter;
        this.appProperties = appProperties;
        this.webhookSecretResolver = webhookSecretResolver;
        this.stateMachineArn = appProperties.aws().stepFunctions().stateMachineArn();
    }

    /**
     * POST /webhooks/jira/pipeline - Replaces JiraWebhookHandler.
     * Validates the Jira webhook token, processes the event, and starts a
     * Step Functions execution for the pipeline workflow.
     */
    @PostMapping("/pipeline")
    public ResponseEntity<Map<String, Object>> handlePipelineWebhook(
            @RequestBody String body,
            @RequestHeader Map<String, String> headers) {

        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);

        try {
            log.info("Jira pipeline webhook received");

            if (body == null || body.isBlank()) {
                log.warn("Empty request body");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Empty request body"));
            }

            WebhookValidator.verifyJiraWebhookToken(headers, webhookSecretResolver.resolve());

            JsonNode payload = objectMapper.readTree(body);
            log.info("Parsed webhook payload for pipeline");

            ProcessResult result = processWebhook(payload, correlationId);

            if (result.skipped()) {
                log.info("Skipped webhook processing: {}", result.reason());
                return ResponseEntity.ok(Map.of(
                        "message", "Webhook processed but skipped",
                        "reason", result.reason()));
            }

            return ResponseEntity.ok(Map.of(
                    "message", "Workflow started",
                    "ticketKey", result.ticketKey(),
                    "executionName", result.executionName()));

        } catch (RateLimitExceededException e) {
            log.warn("Rate limit exceeded: {}", e.getMessage());
            return ResponseEntity.status(429)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            log.warn("Webhook security validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unauthorized: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process webhook", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Internal server error"));
        } finally {
            MDC.remove("correlationId");
        }
    }

    private ProcessResult processWebhook(JsonNode payload, String correlationId) throws Exception {
        JsonNode issue = payload.get("issue");
        if (issue == null) {
            return ProcessResult.skipped("No issue in payload");
        }

        String ticketKey = issue.path("key").asText();
        String ticketId = issue.path("id").asText();
        MDC.put("ticketKey", ticketKey);
        log.info("Processing webhook for ticket: {}", ticketKey);

        if (!TICKET_KEY_PATTERN.matcher(ticketKey).matches()) {
            return ProcessResult.skipped("Invalid ticket key format: " + ticketKey);
        }

        List<String> labels = extractLabels(issue);
        if (!hasValidAiLabel(labels)) {
            return ProcessResult.skipped("No AI-related labels found");
        }

        OperationContext context = extractContext(payload);

        checkRateLimits(ticketKey, context.getUserId().orElse(null));

        if (ticketKey.isBlank()) {
            ticketKey = "UNKNOWN";
        }
        if (ticketId == null || ticketId.isBlank()) {
            ticketId = "UNKNOWN";
        }

        String eventId = extractEventId(ticketId, labels);
        log.info("Checking idempotency for ticket {} with eventId: {}", ticketKey, eventId);
        if (!idempotencyService.checkAndRecord(context.tenantId(), ticketId, ticketKey, eventId)) {
            return ProcessResult.skipped("Duplicate event - already processed (eventId=" + eventId + ")");
        }

        ticketStateRepository.save(
                TicketState.forTicket(context.tenantId(), ticketId, ticketKey, ProcessingStatus.RECEIVED));
        transitionToInProgress(context, ticketKey);

        RepositoryResolver.ResolvedRepository repo = RepositoryResolver.resolve(
                labels, null,
                appProperties.context().defaultWorkspace(),
                appProperties.context().defaultRepo(),
                appProperties.context().defaultPlatform());

        String execName = ticketKey + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> sfnInput = new HashMap<>();
        sfnInput.put("ticketId", ticketId);
        sfnInput.put("ticketKey", ticketKey);
        sfnInput.put("webhookEvent", payload.path("webhookEvent").asText());
        sfnInput.put("dryRun", hasDryRunLabel(labels));
        sfnInput.put("labels", labels);
        sfnInput.put("platform",
                repo != null ? repo.platform().name() : appProperties.context().defaultPlatform());
        sfnInput.put("repoOwner", repo != null ? repo.owner() : "");
        sfnInput.put("repoSlug", repo != null ? repo.repo() : "");
        sfnInput.put("context", Map.of(
                "tenantId", context.tenantId(),
                "userId", context.getUserId().orElse("system"),
                "ticketKey", ticketKey,
                "correlationId", correlationId));

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
            for (JsonNode labelNode : node) {
                labels.add(labelNode.asText().toLowerCase());
            }
        }
        return labels;
    }

    private boolean hasValidAiLabel(List<String> labels) {
        return labels.stream().anyMatch(VALID_AI_LABELS::contains);
    }

    private boolean hasDryRunLabel(List<String> labels) {
        return labels.stream()
                .anyMatch(l -> l.contains("ai-test") || l.contains("dry-run") || l.contains("test-mode"));
    }

    private void transitionToInProgress(OperationContext context, String ticketKey) {
        try {
            jiraClient.updateStatus(context, ticketKey, "In Progress");
        } catch (Exception e) {
            log.info("Transition to In Progress failed/skipped: {}", e.getMessage());
        }
    }

    private void checkRateLimits(String ticketKey, String userId) {
        rateLimiter.consumeOrThrow("ticket:" + ticketKey, appProperties.cost().maxRequestsPerTicketPerHour());
        if (userId != null && !userId.isBlank() && !"system".equals(userId)) {
            rateLimiter.consumeOrThrow("user:" + userId, appProperties.cost().maxRequestsPerUserPerHour());
        }
    }

    private String extractEventId(String ticketId, List<String> labels) {
        long windowSeconds = Instant.now().getEpochSecond() / 10;
        List<String> triggerLabels = labels.stream()
                .filter(l -> l.startsWith("ai-"))
                .sorted()
                .toList();
        return "trigger-" + ticketId + "-" + String.join(",", triggerLabels) + "-w" + windowSeconds;
    }

    private OperationContext extractContext(JsonNode payload) {
        String tenantId = "default";
        if (payload.has("baseUrl")) {
            tenantId = payload.get("baseUrl").asText()
                    .replaceAll("https?://", "")
                    .replaceAll("\\.atlassian\\.net.*", "");
        }
        String userId = "system";
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

    private record ProcessResult(boolean skipped, String reason, String ticketKey, String executionName) {
        static ProcessResult skipped(String reason) {
            return new ProcessResult(true, reason, null, null);
        }

        static ProcessResult success(String ticketKey, String executionName) {
            return new ProcessResult(false, null, ticketKey, executionName);
        }
    }
}
