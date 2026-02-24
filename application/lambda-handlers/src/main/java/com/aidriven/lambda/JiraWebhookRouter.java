package com.aidriven.lambda;

import com.aidriven.core.service.SecretsService;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.aidriven.lambda.factory.ServiceFactory;
import com.aidriven.lambda.security.WebhookValidator;

/**
 * Lightweight router for Jira webhooks.
 *
 * Receives webhooks from API Gateway, validates, extracts key info,
 * and sends to SQS FIFO queue with deduplication. Returns immediately.
 *
 * SQS FIFO handles deduplication (5-minute window), ensuring the processor
 * Lambda is only invoked once per unique ticket+labels combination.
 */
@Slf4j
public class JiraWebhookRouter implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]+-\\d+$");
    private static final List<String> TRIGGER_LABELS = List.of("ai-generate", "ai-test", "dry-run", "test-mode");

    private final ObjectMapper objectMapper;
    private final SqsClient sqsClient;
    private final String queueUrl;
    private final SecretsService secretsService;

    public JiraWebhookRouter() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.objectMapper = factory.getObjectMapper();
        this.sqsClient = factory.getSqsClient();
        this.queueUrl = System.getenv("JIRA_WORKFLOW_QUEUE_URL");
        this.secretsService = factory.getSecretsProvider();
    }

    // For testing
    public JiraWebhookRouter(ObjectMapper objectMapper, SqsClient sqsClient,
                             String queueUrl, SecretsService secretsService) {
        this.objectMapper = objectMapper;
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
        this.secretsService = secretsService;
    }

    @Override
    @Logging(logEvent = true)
    @Tracing
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        LoggingUtils.appendKey("correlationId", context.getAwsRequestId());
        log.info("JiraWebhookRouter invoked");

        try {
            // Token verification is optional - log warning but don't block
            // This matches the old JiraWebhookHandler behavior
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) input.getOrDefault("headers", Map.of());
            String jiraSecretArn = System.getenv("JIRA_WEBHOOK_SECRET_ARN");

            if (jiraSecretArn != null && !jiraSecretArn.isBlank()) {
                try {
                    String expectedToken = secretsService.getSecret(jiraSecretArn);
                    WebhookValidator.verifyJiraWebhookToken(headers, expectedToken);
                } catch (SecurityException e) {
                    // Log but don't block - Jira webhook may not be configured with token
                    log.warn("Jira webhook token verification skipped: {}", e.getMessage());
                } catch (Exception e) {
                    log.warn("Failed to fetch Jira webhook secret: {}", e.getMessage());
                }
            }

            // Parse body
            String body = extractBody(input);
            if (body == null || body.isBlank()) {
                return response(400, Map.of("error", "Empty request body"));
            }

            JsonNode payload = objectMapper.readTree(body);

            // Extract ticket key
            String ticketKey = payload.path("issue").path("key").asText(null);
            if (ticketKey == null || !TICKET_KEY_PATTERN.matcher(ticketKey).matches()) {
                log.info("No valid ticket key in payload");
                return response(200, Map.of("message", "No valid ticket key"));
            }

            // Extract labels
            List<String> labels = extractLabels(payload);
            List<String> triggerLabels = labels.stream()
                    .filter(TRIGGER_LABELS::contains)
                    .sorted()
                    .collect(Collectors.toList());

            if (triggerLabels.isEmpty()) {
                log.info("No trigger labels found for ticket {}", ticketKey);
                return response(200, Map.of("message", "No trigger labels"));
            }

            // Compute deduplication ID: ticket + trigger labels
            // SQS FIFO will dedupe identical messages within 5-minute window
            String dedupId = ticketKey + "-" + String.join(",", triggerLabels);
            String messageGroupId = ticketKey; // All messages for same ticket in same group

            log.info("Routing webhook for ticket {} with labels {} (dedupId={})",
                     ticketKey, triggerLabels, dedupId);

            // Send to SQS FIFO
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageGroupId(messageGroupId)
                    .messageDeduplicationId(dedupId)
                    .build());

            log.info("Queued webhook for ticket {} to SQS FIFO", ticketKey);
            return response(200, Map.of(
                    "message", "Webhook queued",
                    "ticketKey", ticketKey,
                    "dedupId", dedupId));

        } catch (Exception e) {
            log.error("Error routing webhook: {}", e.getMessage(), e);
            return response(500, Map.of("error", "Internal error"));
        }
    }

    private List<String> extractLabels(JsonNode payload) {
        JsonNode labelsNode = payload.path("issue").path("fields").path("labels");
        if (labelsNode.isMissingNode() || !labelsNode.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(labelsNode.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toList());
    }

    private String extractBody(Map<String, Object> input) {
        Object bodyObj = input.get("body");
        if (bodyObj == null) return null;

        String body = bodyObj.toString();
        Boolean isBase64 = (Boolean) input.getOrDefault("isBase64Encoded", false);
        if (Boolean.TRUE.equals(isBase64)) {
            body = new String(Base64.getDecoder().decode(body));
        }
        return body;
    }

    private Map<String, Object> response(int statusCode, Map<String, Object> body) {
        try {
            return Map.of(
                    "statusCode", statusCode,
                    "headers", Map.of("Content-Type", "application/json"),
                    "body", objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            return Map.of("statusCode", 500, "body", "{\"error\":\"Serialization failed\"}");
        }
    }
}
