package com.aidriven.lambda;

import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.service.SecretsService;
import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lambda handler for direct API Gateway → Lambda integration for Jira webhooks.
 * Alternative to SQS-based IngestHandler. Provides lower latency webhook processing.
 *
 * Key differences from IngestHandler:
 * - Receives API Gateway event format (with body, headers, etc.)
 * - Triggers on 'ai-generate' label instead of agent-specific labels
 * - Designed for the new linear workflow (no agent selection)
 *
 * Input (API Gateway proxy format):
 * - body: JSON string containing Jira webhook payload
 * - headers: HTTP headers
 *
 * Output:
 * - statusCode: 200 for success, 4xx/5xx for errors
 * - body: JSON response
 */
@Slf4j
public class JiraWebhookHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]+-\\d+$");
    private static final String AI_GENERATE_LABEL = "ai-generate";
    private static final List<String> VALID_AI_LABELS = List.of(
            "ai-generate", "ai-test", "dry-run", "test-mode"
    );

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final IdempotencyService idempotencyService;
    private final SecretsService secretsService;
    private final SfnClient sfnClient;
    private final String stateMachineArn;
    private final String jiraSecretArn;

    public JiraWebhookHandler() {
        this.objectMapper = new ObjectMapper();

        DynamoDbClient dynamoDbClient = DynamoDbClient.create();
        String tableName = System.getenv("DYNAMODB_TABLE_NAME");

        this.ticketStateRepository = new TicketStateRepository(dynamoDbClient, tableName);
        this.idempotencyService = new IdempotencyService(ticketStateRepository);

        SecretsManagerClient secretsManagerClient = SecretsManagerClient.create();
        this.secretsService = new SecretsService(secretsManagerClient);

        this.sfnClient = SfnClient.create();
        this.stateMachineArn = System.getenv("STATE_MACHINE_ARN");
        this.jiraSecretArn = System.getenv("JIRA_SECRET_ARN");
    }

    // Constructor for testing
    JiraWebhookHandler(ObjectMapper objectMapper, TicketStateRepository ticketStateRepository,
                       IdempotencyService idempotencyService, SecretsService secretsService,
                       SfnClient sfnClient, String stateMachineArn, String jiraSecretArn) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.idempotencyService = idempotencyService;
        this.secretsService = secretsService;
        this.sfnClient = sfnClient;
        this.stateMachineArn = stateMachineArn;
        this.jiraSecretArn = jiraSecretArn;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        log.info("JiraWebhookHandler invoked");

        try {
            // Extract body from API Gateway event
            String body = extractBody(input);
            if (Objects.isNull(body) || body.isBlank()) {
                log.warn("Empty request body");
                return createResponse(400, Map.of("error", "Empty request body"));
            }

            JsonNode payload = objectMapper.readTree(body);
            ProcessResult result = processWebhook(payload);

            if (result.skipped) {
                return createResponse(200, Map.of(
                        "message", "Webhook processed but skipped",
                        "reason", result.reason
                ));
            }

            return createResponse(200, Map.of(
                    "message", "Workflow started",
                    "ticketKey", result.ticketKey,
                    "executionName", result.executionName
            ));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return createResponse(400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process webhook", e);
            return createResponse(500, Map.of("error", "Internal server error"));
        }
    }

    private String extractBody(Map<String, Object> input) {
        // API Gateway proxy format
        if (input.containsKey("body")) {
            Object body = input.get("body");
            if (body instanceof String) {
                // Check if body is base64 encoded
                Boolean isBase64 = (Boolean) input.get("isBase64Encoded");
                if (Boolean.TRUE.equals(isBase64)) {
                    return new String(java.util.Base64.getDecoder().decode((String) body));
                }
                return (String) body;
            }
        }

        // Direct invocation - input itself might be the payload
        if (input.containsKey("issue") || input.containsKey("webhookEvent")) {
            try {
                return objectMapper.writeValueAsString(input);
            } catch (Exception e) {
                log.warn("Failed to serialize input as JSON", e);
            }
        }

        return null;
    }

    private ProcessResult processWebhook(JsonNode payload) throws Exception {
        String webhookEvent = payload.path("webhookEvent").asText("unknown");
        JsonNode issue = payload.get("issue");

        if (Objects.isNull(issue)) {
            return ProcessResult.skipped("No issue in payload");
        }

        String ticketKey = issue.path("key").asText();
        String ticketId = issue.path("id").asText();

        // Validate ticket key format
        if (!TICKET_KEY_PATTERN.matcher(ticketKey).matches()) {
            return ProcessResult.skipped("Invalid ticket key format: " + ticketKey);
        }

        // Check for ai-generate or related labels
        List<String> labels = extractLabels(issue);
        if (!hasValidAiLabel(labels)) {
            return ProcessResult.skipped("No AI-related labels found");
        }

        boolean isDryRun = hasDryRunLabel(labels);

        // Idempotency check - use ticketId to prevent duplicate processing
        if (!idempotencyService.checkAndRecord(ticketId, ticketId)) {
            return ProcessResult.skipped("Duplicate event - already processed");
        }

        // Update ticket state to RECEIVED
        ticketStateRepository.save(TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.RECEIVED));

        // Transition Jira ticket to "In Progress"
        transitionToInProgress(ticketKey);

        // Start Step Functions execution
        String executionName = ticketKey + "-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> sfnInput = Map.of(
                "ticketId", ticketId,
                "ticketKey", ticketKey,
                "webhookEvent", webhookEvent,
                "dryRun", isDryRun
        );

        sfnClient.startExecution(StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(executionName)
                .input(objectMapper.writeValueAsString(sfnInput))
                .build());

        log.info("Started Step Functions execution: {} for ticket {} (dryRun={})",
                executionName, ticketKey, isDryRun);

        return ProcessResult.success(ticketKey, executionName);
    }

    private List<String> extractLabels(JsonNode issue) {
        List<String> labels = new ArrayList<>();
        JsonNode labelsNode = issue.path("fields").path("labels");
        if (labelsNode.isArray()) {
            for (JsonNode label : labelsNode) {
                labels.add(label.asText().toLowerCase());
            }
        }
        return labels;
    }

    private boolean hasValidAiLabel(List<String> labels) {
        return labels.stream()
                .anyMatch(label -> VALID_AI_LABELS.stream().anyMatch(label::contains));
    }

    private boolean hasDryRunLabel(List<String> labels) {
        return labels.stream()
                .anyMatch(label -> label.contains("ai-test")
                        || label.contains("dry-run")
                        || label.contains("test-mode"));
    }

    private void transitionToInProgress(String ticketKey) {
        try {
            JiraClient jiraClient = JiraClient.fromSecrets(secretsService, jiraSecretArn);
            jiraClient.updateStatus(ticketKey, "In Progress");
        } catch (IllegalStateException e) {
            // Transition not available - might already be in progress
            log.info("Could not transition to In Progress: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to update Jira status", e);
        }
    }

    private Map<String, Object> createResponse(int statusCode, Map<String, Object> body) {
        try {
            return Map.of(
                    "statusCode", statusCode,
                    "headers", Map.of(
                            "Content-Type", "application/json",
                            "Access-Control-Allow-Origin", "*"
                    ),
                    "body", objectMapper.writeValueAsString(body)
            );
        } catch (Exception e) {
            return Map.of(
                    "statusCode", 500,
                    "body", "{\"error\":\"Failed to serialize response\"}"
            );
        }
    }

    private static class ProcessResult {
        final boolean skipped;
        final String reason;
        final String ticketKey;
        final String executionName;

        private ProcessResult(boolean skipped, String reason, String ticketKey, String executionName) {
            this.skipped = skipped;
            this.reason = reason;
            this.ticketKey = ticketKey;
            this.executionName = executionName;
        }

        static ProcessResult skipped(String reason) {
            return new ProcessResult(true, reason, null, null);
        }

        static ProcessResult success(String ticketKey, String executionName) {
            return new ProcessResult(false, null, ticketKey, executionName);
        }
    }
}
