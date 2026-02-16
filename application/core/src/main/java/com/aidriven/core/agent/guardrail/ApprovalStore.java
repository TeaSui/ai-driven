package com.aidriven.core.agent.guardrail;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB-backed store for pending tool approvals.
 * When a HIGH-risk tool call is intercepted, it's stored here awaiting human approval.
 *
 * <p>Key schema (uses existing single-table):
 * <ul>
 *   <li>PK: AGENT#{ticketKey}</li>
 *   <li>SK: APPROVAL#{timestamp}</li>
 *   <li>TTL: 24 hours (auto-expire unanswered approvals)</li>
 * </ul>
 */
@Slf4j
public class ApprovalStore {

    private static final long APPROVAL_TTL_SECONDS = 24 * 3600; // 24 hours

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public ApprovalStore(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    /**
     * Store a pending approval for a tool call.
     *
     * @param ticketKey    Jira ticket key
     * @param toolCallId   Claude tool_use ID
     * @param toolName     Tool name (e.g., "source_control_merge_pr")
     * @param toolInputJson Serialized tool input arguments
     * @param riskLevel    Risk level that triggered approval
     * @param prompt       Human-readable approval prompt
     * @param requestedBy  Who triggered the original command
     */
    public void storePendingApproval(String ticketKey, String toolCallId, String toolName,
                                      String toolInputJson, RiskLevel riskLevel, String prompt,
                                      String requestedBy) {
        Instant now = Instant.now();
        String pk = "AGENT#" + ticketKey;
        String sk = "APPROVAL#" + now.toString();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(pk));
        item.put("SK", AttributeValue.fromS(sk));
        item.put("toolCallId", AttributeValue.fromS(toolCallId));
        item.put("toolName", AttributeValue.fromS(toolName));
        item.put("toolInputJson", AttributeValue.fromS(toolInputJson));
        item.put("riskLevel", AttributeValue.fromS(riskLevel.name()));
        item.put("approvalPrompt", AttributeValue.fromS(prompt));
        item.put("requestedBy", AttributeValue.fromS(requestedBy));
        item.put("requestedAt", AttributeValue.fromS(now.toString()));
        item.put("status", AttributeValue.fromS("PENDING"));
        item.put("ttl", AttributeValue.fromN(
                String.valueOf(now.plusSeconds(APPROVAL_TTL_SECONDS).getEpochSecond())));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

        log.info("Stored pending approval for ticket={} tool={} id={}", ticketKey, toolName, toolCallId);
    }

    /**
     * Find the latest pending approval for a ticket.
     *
     * @param ticketKey Jira ticket key
     * @return The latest pending approval, or empty if none
     */
    public Optional<PendingApproval> getLatestPending(String ticketKey) {
        String pk = "AGENT#" + ticketKey;

        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
                .filterExpression("#s = :pending")
                .expressionAttributeNames(Map.of("#s", "status"))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(pk),
                        ":prefix", AttributeValue.fromS("APPROVAL#"),
                        ":pending", AttributeValue.fromS("PENDING")))
                .scanIndexForward(false) // newest first
                .limit(1)
                .build());

        if (response.items().isEmpty()) {
            return Optional.empty();
        }

        Map<String, AttributeValue> item = response.items().get(0);
        return Optional.of(new PendingApproval(
                item.get("PK").s(),
                item.get("SK").s(),
                item.get("toolCallId").s(),
                item.get("toolName").s(),
                item.get("toolInputJson").s(),
                RiskLevel.valueOf(item.get("riskLevel").s()),
                item.get("approvalPrompt").s(),
                item.get("requestedBy").s(),
                Instant.parse(item.get("requestedAt").s())));
    }

    /**
     * Mark a pending approval as approved and remove it.
     *
     * @param ticketKey Jira ticket key
     * @param sk        The sort key of the approval to consume
     */
    public void consumeApproval(String ticketKey, String sk) {
        String pk = "AGENT#" + ticketKey;
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.fromS(pk),
                        "SK", AttributeValue.fromS(sk)))
                .updateExpression("SET #s = :approved, approvedAt = :now")
                .expressionAttributeNames(Map.of("#s", "status"))
                .expressionAttributeValues(Map.of(
                        ":approved", AttributeValue.fromS("APPROVED"),
                        ":now", AttributeValue.fromS(Instant.now().toString())))
                .build());

        log.info("Consumed approval for ticket={} sk={}", ticketKey, sk);
    }

    /**
     * Represents a pending approval awaiting human confirmation.
     */
    public record PendingApproval(
            String pk,
            String sk,
            String toolCallId,
            String toolName,
            String toolInputJson,
            RiskLevel riskLevel,
            String approvalPrompt,
            String requestedBy,
            Instant requestedAt) {
    }
}
