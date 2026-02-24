package com.aidriven.lambda;

import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.lambda.factory.ServiceFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SendTaskSuccessRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.aidriven.spi.model.OperationContext;

/**
 * Lambda handler for managing the wait-for-merge step in the workflow.
 * Standardized with direct dependency injection via ServiceFactory.
 */
@Slf4j
@RequiredArgsConstructor
public class MergeWaitHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String TOKEN_PK_PREFIX = "TASK_TOKEN#";
    private static final String TOKEN_SK = "TOKEN";
    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private static final Set<String> MERGE_EVENT_TYPES = Set.of("pr:merged", "pullrequest:fulfilled");

    private final ObjectMapper objectMapper;
    private final DynamoDbClient dynamoDbClient;
    private final SfnClient sfnClient;
    private final String tableName;
    private final TicketStateRepository ticketStateRepository;

    /** No-arg constructor required by AWS Lambda runtime. */
    public MergeWaitHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.objectMapper = factory.getObjectMapper();
        this.dynamoDbClient = factory.getDynamoDbClient();
        this.sfnClient = factory.getSfnClient();
        this.tableName = factory.getAppConfig().getDynamoDbTableName();
        this.ticketStateRepository = factory.getTicketStateRepository();
    }

    @Override
    @Logging(logEvent = true)
    @Tracing
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String ticketKey = (String) input.get("ticketKey");
        if (ticketKey != null) {
            LoggingUtils.appendKey("ticketKey", ticketKey);
        }
        LoggingUtils.appendKey("correlationId", context.getAwsRequestId());

        log.info("MergeWaitHandler invoked with input keys: {}", input.keySet());

        try {
            // Determine mode based on input
            if (input.containsKey("token")) {
                // REGISTRATION MODE: Step Functions is passing a task token
                return handleRegistration(input);
            } else if (input.containsKey("webhookEvent") || input.containsKey("body")
                    || input.containsKey("eventKey")) {
                // CALLBACK MODE: Webhook event
                return handleWebhook(input);
            } else {
                log.warn("Unknown invocation mode - neither token nor webhookEvent present");
                return Map.of("error", "Unknown invocation mode");
            }
        } catch (Exception e) {
            log.error("Failed to process MergeWaitHandler request", e);
            return Map.of("error", e.getMessage());
        } finally {
            // Context cleared by Powertools
        }
    }

    private Map<String, Object> handleRegistration(Map<String, Object> input) {
        String token = (String) input.get("token");
        String prUrl = (String) input.get("prUrl");
        String ticketId = (String) input.get("ticketId");
        String ticketKey = (String) input.get("ticketKey");

        log.info("Registering task token for PR: {} (ticket: {})", prUrl, ticketKey);

        OperationContext tenantContext = extractTenantContext(input);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s(TOKEN_PK_PREFIX + prUrl).build());
        item.put("SK", AttributeValue.builder().s(TOKEN_SK).build());
        item.put("token", AttributeValue.builder().s(token).build());
        item.put("tenantId", AttributeValue.builder().s(tenantContext.getTenantId()).build());
        item.put("ticketId", AttributeValue.builder().s(ticketId).build());
        item.put("ticketKey", AttributeValue.builder().s(ticketKey).build());
        item.put("prUrl", AttributeValue.builder().s(prUrl).build());
        item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());
        item.put("ttl",
                AttributeValue.builder().n(String.valueOf(Instant.now().plus(TOKEN_TTL).getEpochSecond())).build());

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

        return Map.of("registered", true, "prUrl", prUrl);
    }

    private Map<String, Object> handleWebhook(Map<String, Object> input) throws Exception {
        // Extract PR URL from Bitbucket webhook payload
        String prUrl = extractPrUrlFromWebhook(input);
        if (prUrl == null) {
            log.warn("No PR URL found in webhook payload");
            return Map.of("status", "ignored", "reason", "no_pr_url");
        }

        // Check if the event is a MERGE
        if (!isMergeEvent(input)) {
            log.info("Ignoring non-merge event for PR: {}", prUrl);
            return Map.of("status", "ignored", "reason", "not_merge_event");
        }

        log.info("Processing merge event for PR: {}", prUrl);

        // Lookup task token
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("PK", AttributeValue.builder().s(TOKEN_PK_PREFIX + prUrl).build());
        key.put("SK", AttributeValue.builder().s(TOKEN_SK).build());

        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build());

        if (!response.hasItem()) {
            log.warn("No task token found for PR: {}", prUrl);
            return Map.of("status", "ignored", "reason", "no_token_found");
        }

        Map<String, AttributeValue> item = response.item();
        String token = item.get("token").s();
        String ticketId = item.get("ticketId").s();
        String ticketKey = item.get("ticketKey").s();
        String tenantId = item.get("tenantId") != null ? item.get("tenantId").s() : "default";

        // Resume Step Functions workflow
        log.info("Resuming workflow for ticket: {} with token for PR: {}", ticketKey, prUrl);

        Map<String, Object> output = Map.of(
                "status", "merged",
                "prUrl", prUrl,
                "ticketId", ticketId,
                "ticketKey", ticketKey);

        sfnClient.sendTaskSuccess(SendTaskSuccessRequest.builder()
                .taskToken(token)
                .output(objectMapper.writeValueAsString(output))
                .build());

        // Update ticket state
        ticketStateRepository.save(TicketState.forTicket(tenantId, ticketId, ticketKey, ProcessingStatus.DONE));

        // Clean up token from DynamoDB
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build());

        return Map.of("resumed", true, "ticketKey", ticketKey);
    }

    private String extractPrUrlFromWebhook(Map<String, Object> input) {
        try {
            String body = (String) input.get("body");
            if (body == null)
                return null;

            JsonNode payload = objectMapper.readTree(body);
            // Bitbucket Server/Cloud schema variations
            JsonNode pullRequest = payload.path("pullRequest");
            if (pullRequest.isMissingNode()) {
                pullRequest = payload.path("pullrequest"); // variant
            }

            if (!pullRequest.isMissingNode()) {
                // Try to find the link to the PR
                JsonNode links = pullRequest.path("links");
                if (links.has("self") && links.get("self").isArray()) {
                    return links.get("self").get(0).path("href").asText();
                }
                if (links.has("html")) {
                    return links.path("html").path("href").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract PR URL from webhook", e);
        }
        return null;
    }

    private boolean isMergeEvent(Map<String, Object> input) {
        try {
            String body = (String) input.get("body");
            if (body == null)
                return false;

            JsonNode payload = objectMapper.readTree(body);
            String eventKey = payload.path("eventKey").asText("");
            if (eventKey.isEmpty()) {
                eventKey = (String) input.get("X-Event-Key"); // Header based lookup
            }

            return MERGE_EVENT_TYPES.stream().anyMatch(eventKey::equalsIgnoreCase);
        } catch (Exception e) {
            return false;
        }
    }

    private OperationContext extractTenantContext(Map<String, Object> input) {
        if (!input.containsKey("context") || !(input.get("context") instanceof Map)) {
            return OperationContext.builder().tenantId("default").build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) input.get("context");
        String tenantId = (String) context.getOrDefault("tenantId", "default");
        String userId = (String) context.getOrDefault("userId", "system");
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = (Map<String, String>) context.getOrDefault("metadata", Map.of());
        return OperationContext.builder()
                .tenantId(tenantId)
                .userId(userId)
                .build();
    }
}
