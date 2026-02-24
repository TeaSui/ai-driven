package com.aidriven.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

/**
 * DynamoDB entity representing the state of a ticket in processing.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class TicketState {

    private @NonNull String pk;
    private @NonNull String sk;
    private String gsi1pk;
    private String gsi1sk;

    private String ticketId;
    private @NonNull String ticketKey;
    private String status;
    private String agentType;
    private String prUrl;
    private String branchName;
    private String errorMessage;
    private Long ttl;
    private Instant createdAt;
    private Instant updatedAt;

    // Cost tracking fields (impl-12)
    private Integer inputTokens;
    private Integer outputTokens;
    private Double estimatedCostUsd;
    private Boolean costWarningSent;

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

    @DynamoDbSecondaryPartitionKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1PK")
    public String getGsi1pk() {
        return gsi1pk;
    }

    @DynamoDbSecondarySortKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1SK")
    public String getGsi1sk() {
        return gsi1sk;
    }

    /**
     * Creates a partition key for a ticket.
     */
    public static String createPk(String tenantId, String ticketId) {
        return "TICKET#" + tenantId + "#" + ticketId;
    }

    /**
     * Creates a sort key for state records (historical, with timestamp).
     */
    public static String createStateSk(Instant timestamp) {
        return "STATE#" + timestamp.toString();
    }

    /**
     * Creates a fixed sort key for the current state record.
     * Using a fixed SK ensures updates overwrite the same record.
     */
    public static String createCurrentStateSk() {
        return "STATE#CURRENT";
    }

    /**
     * Creates a sort key for idempotency records.
     */
    public static String createIdempotencySk(String eventId) {
        return "IDEMPOTENCY#" + eventId;
    }

    /**
     * Creates a GSI1 partition key for status queries.
     */
    public static String createStatusGsi1Pk(ProcessingStatus status) {
        return "STATUS#" + status.getValue();
    }

    /**
     * Factory method to create a TicketState with common fields populated.
     * Reduces boilerplate in handlers.
     */
    public static TicketState forTicket(String tenantId, String ticketId, String ticketKey, ProcessingStatus status) {
        return TicketState.builder()
                .pk(createPk(tenantId, ticketId))
                .sk(createCurrentStateSk())
                .gsi1pk(createStatusGsi1Pk(status))
                .gsi1sk(createPk(tenantId, ticketId))
                .ticketId(ticketId)
                .ticketKey(ticketKey)
                .status(status.getValue())
                .build();
    }

    /**
     * Returns a copy of this state with the agent type set.
     */
    public TicketState withAgentType(String agentType) {
        return this.toBuilder().agentType(agentType).build();
    }

    /**
     * Returns a copy of this state with the error message set.
     */
    public TicketState withError(String errorMessage) {
        return this.toBuilder().errorMessage(errorMessage).build();
    }

    /**
     * Returns a copy of this state with PR details set.
     */
    public TicketState withPrDetails(String prUrl, String branchName) {
        return this.toBuilder().prUrl(prUrl).branchName(branchName).build();
    }
}
