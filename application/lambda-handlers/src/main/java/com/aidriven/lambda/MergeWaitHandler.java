package com.aidriven.lambda;

import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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
import java.util.Objects;

/**
 * Lambda handler for managing the wait-for-merge step in the workflow.
 * Operates in two modes:
 *
 * 1. REGISTRATION MODE (called from Step Functions with waitForTaskToken):
 *    - Receives task token from Step Functions
 *    - Stores token in DynamoDB with PR URL as the lookup key
 *    - Returns immediately (Step Functions waits for callback)
 *
 * 2. CALLBACK MODE (called from Bitbucket webhook via API Gateway):
 *    - Receives PR merge event from Bitbucket
 *    - Looks up stored task token by PR URL
 *    - Calls SendTaskSuccess to resume the Step Functions execution
 *
 * DynamoDB Schema for task tokens:
 * - PK: TASK_TOKEN#{prUrl}
 * - SK: TOKEN
 * - Attributes: token, ticketId, ticketKey, prUrl, createdAt, ttl
 */
@Slf4j
public class MergeWaitHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String TOKEN_PK_PREFIX = "TASK_TOKEN#";
    private static final String TOKEN_SK = "TOKEN";
    private static final Duration TOKEN_TTL = Duration.ofDays(7);

    private final ObjectMapper objectMapper;
    private final DynamoDbClient dynamoDbClient;
    private final SfnClient sfnClient;
    private final String tableName;
    private final TicketStateRepository ticketStateRepository;

    public MergeWaitHandler() {
        this.objectMapper = new ObjectMapper();
        this.dynamoDbClient = DynamoDbClient.create();
        this.sfnClient = SfnClient.create();
        this.tableName = System.getenv("DYNAMODB_TABLE_NAME");
        this.ticketStateRepository = new TicketStateRepository(dynamoDbClient, tableName);
    }

    // Constructor for testing
    MergeWaitHandler(ObjectMapper objectMapper, DynamoDbClient dynamoDbClient, SfnClient sfnClient,
                     String tableName, TicketStateRepository ticketStateRepository) {
        this.objectMapper = objectMapper;
        this.dynamoDbClient = dynamoDbClient;
        this.sfnClient = sfnClient;
        this.tableName = tableName;
        this.ticketStateRepository = ticketStateRepository;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        log.info("MergeWaitHandler invoked with input keys: {}", input.keySet());

        // Determine mode based on input
        if (input.containsKey("token")) {
            // REGISTRATION MODE: Step Functions is passing a task token
            return handleRegistration(input);
        } else if (input.containsKey("body") || input.containsKey("pullrequest")) {
            // CALLBACK MODE: API Gateway webhook or direct Bitbucket event
            return handleCallback(input);
        } else {
            log.error("Unknown invocation mode. Input: {}", input);
            throw new IllegalArgumentException("Unknown invocation mode - expected 'token' for registration or 'body'/'pullrequest' for callback");
        }
    }

    /**
     * Registration mode: Store the task token for later callback.
     */
    private Map<String, Object> handleRegistration(Map<String, Object> input) {
        String token = (String) input.get("token");
        String ticketId = (String) input.get("ticketId");
        String ticketKey = (String) input.get("ticketKey");
        String prUrl = (String) input.get("prUrl");

        Objects.requireNonNull(token, "token is required");
        Objects.requireNonNull(prUrl, "prUrl is required");

        log.info("Registering task token for PR: {} (ticket: {})", prUrl, ticketKey);

        // Store token in DynamoDB
        long ttlEpoch = Instant.now().plus(TOKEN_TTL).getEpochSecond();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s(TOKEN_PK_PREFIX + normalizeUrl(prUrl)).build());
        item.put("SK", AttributeValue.builder().s(TOKEN_SK).build());
        item.put("token", AttributeValue.builder().s(token).build());
        item.put("ticketId", AttributeValue.builder().s(ticketId != null ? ticketId : "").build());
        item.put("ticketKey", AttributeValue.builder().s(ticketKey != null ? ticketKey : "").build());
        item.put("prUrl", AttributeValue.builder().s(prUrl).build());
        item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());
        item.put("ttl", AttributeValue.builder().n(String.valueOf(ttlEpoch)).build());

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK)")
                    .build());
            log.info("Task token registered successfully for PR: {}", prUrl);
        } catch (ConditionalCheckFailedException e) {
            // Token already exists, update it
            log.warn("Token already exists for PR: {}, updating", prUrl);
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());
        }

        // Update ticket state to indicate waiting for merge
        if (Objects.nonNull(ticketId) && Objects.nonNull(ticketKey)) {
            ticketStateRepository.save(
                    TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.IN_REVIEW)
                            .withPrDetails(prUrl, null));
        }

        return Map.of(
                "registered", true,
                "prUrl", prUrl,
                "ttlSeconds", TOKEN_TTL.getSeconds()
        );
    }

    /**
     * Callback mode: Handle PR merge webhook and resume Step Functions.
     */
    private Map<String, Object> handleCallback(Map<String, Object> input) {
        try {
            // Parse webhook payload
            String prUrl = extractPrUrlFromWebhook(input);
            if (Objects.isNull(prUrl)) {
                log.warn("Could not extract PR URL from webhook");
                return Map.of("processed", false, "reason", "Could not extract PR URL");
            }

            log.info("Processing merge callback for PR: {}", prUrl);

            // Look up the stored token
            String normalizedUrl = normalizeUrl(prUrl);
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.builder().s(TOKEN_PK_PREFIX + normalizedUrl).build(),
                            "SK", AttributeValue.builder().s(TOKEN_SK).build()
                    ))
                    .build());

            if (!response.hasItem() || response.item().isEmpty()) {
                log.info("No task token found for PR: {} - may have already been processed or TTL expired", prUrl);
                return Map.of(
                        "processed", false,
                        "reason", "No pending task token for this PR"
                );
            }

            Map<String, AttributeValue> item = response.item();
            String token = item.get("token").s();
            String ticketId = item.containsKey("ticketId") ? item.get("ticketId").s() : null;
            String ticketKey = item.containsKey("ticketKey") ? item.get("ticketKey").s() : null;

            log.info("Found task token for PR: {}, ticket: {}", prUrl, ticketKey);

            // Send success to Step Functions
            String output = objectMapper.writeValueAsString(Map.of(
                    "merged", true,
                    "prUrl", prUrl,
                    "ticketId", Objects.toString(ticketId, ""),
                    "ticketKey", Objects.toString(ticketKey, ""),
                    "mergedAt", Instant.now().toString()
            ));

            sfnClient.sendTaskSuccess(SendTaskSuccessRequest.builder()
                    .taskToken(token)
                    .output(output)
                    .build());

            log.info("Step Functions execution resumed for ticket: {}", ticketKey);

            // Delete the token record
            dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.builder().s(TOKEN_PK_PREFIX + normalizedUrl).build(),
                            "SK", AttributeValue.builder().s(TOKEN_SK).build()
                    ))
                    .build());

            // Update ticket state
            if (Objects.nonNull(ticketId) && Objects.nonNull(ticketKey) && !ticketId.isEmpty()) {
                ticketStateRepository.save(
                        TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.DONE));
            }

            return Map.of(
                    "processed", true,
                    "prUrl", prUrl,
                    "ticketKey", Objects.toString(ticketKey, "")
            );

        } catch (Exception e) {
            log.error("Failed to process merge callback", e);
            throw new RuntimeException("Failed to process merge callback", e);
        }
    }

    /**
     * Extracts the PR URL from various webhook payload formats.
     */
    @SuppressWarnings("unchecked")
    private String extractPrUrlFromWebhook(Map<String, Object> input) {
        try {
            // Check for API Gateway wrapped body
            if (input.containsKey("body")) {
                String body = (String) input.get("body");
                JsonNode json = objectMapper.readTree(body);
                return extractPrUrlFromJson(json);
            }

            // Direct Bitbucket event format
            if (input.containsKey("pullrequest")) {
                Map<String, Object> pr = (Map<String, Object>) input.get("pullrequest");
                Map<String, Object> links = (Map<String, Object>) pr.get("links");
                if (links != null) {
                    Map<String, Object> html = (Map<String, Object>) links.get("html");
                    if (html != null) {
                        return (String) html.get("href");
                    }
                }
            }

            // Try to parse as JSON node
            JsonNode json = objectMapper.valueToTree(input);
            return extractPrUrlFromJson(json);

        } catch (Exception e) {
            log.warn("Failed to extract PR URL: {}", e.getMessage());
            return null;
        }
    }

    private String extractPrUrlFromJson(JsonNode json) {
        // Bitbucket format: pullrequest.links.html.href
        JsonNode prNode = json.path("pullrequest");
        if (!prNode.isMissingNode()) {
            JsonNode href = prNode.path("links").path("html").path("href");
            if (!href.isMissingNode()) {
                return href.asText();
            }
        }

        // Alternative format: pr_url or prUrl
        if (json.has("pr_url")) {
            return json.get("pr_url").asText();
        }
        if (json.has("prUrl")) {
            return json.get("prUrl").asText();
        }

        return null;
    }

    /**
     * Normalizes a URL to ensure consistent lookup keys.
     */
    private String normalizeUrl(String url) {
        if (Objects.isNull(url)) {
            return "";
        }
        // Remove trailing slashes and convert to lowercase for consistency
        String normalized = url.trim().toLowerCase();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
