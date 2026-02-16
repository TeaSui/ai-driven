package com.aidriven.core.agent;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Map;

/**
 * Tracks cumulative token cost per ticket conversation.
 * Enforces a configurable budget to prevent cost runaway.
 *
 * <p>Key schema (uses existing single-table):
 * <ul>
 *   <li>PK: AGENT#{ticketKey}</li>
 *   <li>SK: COST_SUMMARY</li>
 * </ul>
 */
@Slf4j
public class CostTracker {

    private static final String SK_COST = "COST_SUMMARY";
    private static final long COST_TTL_SECONDS = 30 * 24 * 3600; // 30 days

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final int budgetTokens;

    public CostTracker(DynamoDbClient dynamoDbClient, String tableName, int budgetTokens) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.budgetTokens = budgetTokens;
    }

    /**
     * Check if the ticket has remaining budget.
     *
     * @param ticketKey Jira ticket key
     * @return true if the ticket is within budget
     */
    public boolean hasRemainingBudget(String ticketKey) {
        int used = getTotalTokensUsed(ticketKey);
        boolean withinBudget = used < budgetTokens;
        if (!withinBudget) {
            log.warn("Ticket {} exceeded token budget: used={} budget={}", ticketKey, used, budgetTokens);
        }
        return withinBudget;
    }

    /**
     * Get the total tokens used for a ticket.
     */
    public int getTotalTokensUsed(String ticketKey) {
        String pk = "AGENT#" + ticketKey;
        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS(pk),
                            "SK", AttributeValue.fromS(SK_COST)))
                    .build());

            if (!response.hasItem() || response.item().isEmpty()) {
                return 0;
            }
            return Integer.parseInt(response.item().get("totalTokens").n());
        } catch (Exception e) {
            log.warn("Failed to read cost summary for ticket={}: {}", ticketKey, e.getMessage());
            return 0;
        }
    }

    /**
     * Add tokens to the running total for a ticket.
     * Uses DynamoDB atomic ADD to prevent race conditions.
     *
     * @param ticketKey Jira ticket key
     * @param tokens    Tokens consumed in this turn
     */
    public void addTokens(String ticketKey, int tokens) {
        if (tokens <= 0) return;

        String pk = "AGENT#" + ticketKey;
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS(pk),
                            "SK", AttributeValue.fromS(SK_COST)))
                    .updateExpression("ADD totalTokens :t SET updatedAt = :now, #ttl = :ttl")
                    .expressionAttributeNames(Map.of("#ttl", "ttl"))
                    .expressionAttributeValues(Map.of(
                            ":t", AttributeValue.fromN(String.valueOf(tokens)),
                            ":now", AttributeValue.fromS(Instant.now().toString()),
                            ":ttl", AttributeValue.fromN(String.valueOf(
                                    Instant.now().plusSeconds(COST_TTL_SECONDS).getEpochSecond()))))
                    .build());

            log.debug("Added {} tokens for ticket={}", tokens, ticketKey);
        } catch (Exception e) {
            log.error("Failed to update cost tracker for ticket={}: {}", ticketKey, e.getMessage());
            // Non-fatal: don't block processing if cost tracking fails
        }
    }

    /**
     * Get remaining token budget for a ticket.
     */
    public int getRemainingBudget(String ticketKey) {
        return Math.max(0, budgetTokens - getTotalTokensUsed(ticketKey));
    }
}
