package com.aidriven.lambda;

import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.IdempotencyService;

import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.Base64;

import com.aidriven.lambda.factory.ServiceFactory;

/**
 * Lambda handler for direct API Gateway → Lambda integration for Jira webhooks.
 */
@Slf4j
@RequiredArgsConstructor
public class JiraWebhookHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]+-\\d+$");
    private static final List<String> VALID_AI_LABELS = List.of("ai-generate", "ai-test", "dry-run", "test-mode");

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final IdempotencyService idempotencyService;
    private final JiraClient jiraClient;
    private final SfnClient sfnClient;
    private final String stateMachineArn;

    /** No-arg constructor required by AWS Lambda runtime. */
    public JiraWebhookHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.objectMapper = factory.getObjectMapper();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.idempotencyService = factory.getIdempotencyService();
        this.jiraClient = factory.getJiraClient();
        this.sfnClient = factory.getSfnClient();
        this.stateMachineArn = factory.getAppConfig().getStateMachineArn().orElse("");
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

            JsonNode payload = objectMapper.readTree(body);
            ProcessResult result = processWebhook(payload);

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

    private ProcessResult processWebhook(JsonNode payload) throws Exception {
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

        if (!idempotencyService.checkAndRecord(ticketId, ticketId)) {
            return ProcessResult.skipped("Duplicate event - already processed");
        }

        ticketStateRepository.save(TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.RECEIVED));
        transitionToInProgress(ticketKey);

        String execName = ticketKey + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> sfnInput = Map.of(
                "ticketId", ticketId,
                "ticketKey", ticketKey,
                "webhookEvent", payload.path("webhookEvent").asText("unknown"),
                "dryRun", hasDryRunLabel(labels));

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
        return labels.stream().anyMatch(l -> VALID_AI_LABELS.stream().anyMatch(l::contains));
    }

    private boolean hasDryRunLabel(List<String> labels) {
        return labels.stream().anyMatch(l -> l.contains("ai-test") || l.contains("dry-run") || l.contains("test-mode"));
    }

    private void transitionToInProgress(String ticketKey) {
        try {
            jiraClient.updateStatus(ticketKey, "In Progress");
        } catch (Exception e) {
            log.info("Transition to In Progress failed/skipped: {}", e.getMessage());
        }
    }

    private Map<String, Object> createResponse(int status, Map<String, Object> body) {
        try {
            return Map.of("statusCode", status,
                    "headers", Map.of("Content-Type", "application/json", "Access-Control-Allow-Origin", "*"),
                    "body", objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            return Map.of("statusCode", 500, "body", "{\"error\":\"Internal Failure\"}");
        }
    }

    private record ProcessResult(boolean skipped, String reason, String ticketKey, String executionName) {
        static ProcessResult skipped(String r) {
            return new ProcessResult(true, r, null, null);
        }

        static ProcessResult success(String k, String e) {
            return new ProcessResult(false, null, k, e);
        }
    }
}
