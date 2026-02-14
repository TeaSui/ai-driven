package com.aidriven.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;
import java.util.List;

/**
 * DynamoDB entity recording quality metrics for each AI code-generation run.
 *
 * <p>
 * Stored in the same table as {@link TicketState} using the single-table
 * design: PK = {@code METRICS#ticketKey}, SK = {@code GEN#timestamp}.
 * </p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class GenerationMetrics {

    private String pk;
    private String sk;

    private String ticketKey;
    private String model;
    private String promptVersion;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer filesGenerated;
    private Boolean prApproved;
    private Long timeToApprovalSeconds;
    private List<String> ticketLabels;
    private Instant createdAt;
    private Long ttl;

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

    /**
     * Factory for recording a new generation event.
     */
    public static GenerationMetrics forGeneration(String ticketKey, String model,
            String promptVersion,
            int inputTokens, int outputTokens,
            int filesGenerated,
            List<String> ticketLabels) {
        Instant now = Instant.now();
        return GenerationMetrics.builder()
                .pk("METRICS#" + ticketKey)
                .sk("GEN#" + now.toString())
                .ticketKey(ticketKey)
                .model(model)
                .promptVersion(promptVersion)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .filesGenerated(filesGenerated)
                .ticketLabels(ticketLabels)
                .createdAt(now)
                .build();
    }

    /**
     * Marks this metrics record as PR approved/rejected and computes the
     * time-to-approval from {@link #createdAt}.
     */
    public GenerationMetrics withApprovalResult(boolean approved) {
        GenerationMetrics.GenerationMetricsBuilder b = this.toBuilder().prApproved(approved);
        if (approved && this.createdAt != null) {
            b.timeToApprovalSeconds(
                    Instant.now().getEpochSecond() - this.createdAt.getEpochSecond());
        }
        return b.build();
    }
}
