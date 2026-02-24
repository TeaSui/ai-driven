package com.aidriven.core.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

/**
 * DynamoDB entity representing a single message in an agent conversation.
 * Uses the existing single-table design.
 *
 * <p>
 * Key schema:
 * <ul>
 * <li>PK: CONV#{tenantId}#{ticketKey} (e.g., CONV#acme#ONC-10001)</li>
 * <li>SK: MSG#{timestamp}#{sequence} (e.g.,
 * MSG#2026-02-15T10:30:00.000Z#001)</li>
 * </ul>
 *
 * <p>
 * TTL: 30-day auto-cleanup to prevent unbounded growth.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class ConversationMessage {

    private String pk;
    private String sk;

    /** "user" or "assistant" */
    private String role;

    /** Jira display name (user) or "ai-agent" (assistant) */
    private String author;

    /** Raw Claude message content blocks as JSON string */
    private String contentJson;

    /** Jira comment ID for traceability */
    private String commentId;

    /** ISO-8601 timestamp */
    private Instant timestamp;

    /** Token count for budget tracking */
    private int tokenCount;

    /** TTL for DynamoDB auto-cleanup (epoch seconds) */
    private Long ttl;

    // --- DynamoDB key annotations ---

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() {
        return pk;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() {
        return sk;
    }

    // --- Key factory methods ---

    /** Creates partition key for agent conversations. */
    public static String createPk(String tenantId, String ticketKey) {
        return "CONV#" + tenantId + "#" + ticketKey;
    }

    /** Creates sort key with timestamp and sequence for ordering. */
    public static String createSk(Instant timestamp, int sequence) {
        return String.format("MSG#%s#%03d", timestamp.toString(), sequence);
    }

    /** TTL: 30 days from now (epoch seconds). */
    public static long defaultTtl() {
        return Instant.now().plusSeconds(30 * 24 * 3600).getEpochSecond();
    }
}
