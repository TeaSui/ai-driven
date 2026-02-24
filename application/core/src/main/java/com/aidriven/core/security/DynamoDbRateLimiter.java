package com.aidriven.core.security;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple fixed-window rate limiter utilizing DynamoDB atomic counters.
 * It uses the ai-driven-state table with a specific PK format.
 */
@Slf4j
public class DynamoDbRateLimiter implements RateLimiter {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbRateLimiter(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    /**
     * Consumes one request from the current hour's quota for the given key.
     *
     * @param key                Consists of context like "user:U12345" or
     *                           "ticket:CRM-88"
     * @param maxRequestsPerHour Maximum allowed requests in the current hour window
     * @throws RateLimitExceededException if quota is exhausted
     */
    @Override
    public void consumeOrThrow(String key, int maxRequestsPerHour) throws RateLimitExceededException {
        // Use the current hour as the fixed window
        Instant now = Instant.now();
        Instant hourStart = now.truncatedTo(ChronoUnit.HOURS);

        // e.g., RATELIMIT#user:U12345#2023-10-24T10:00:00Z
        String partitionKey = "RATELIMIT#" + key + "#" + hourStart.atZone(ZoneOffset.UTC).toLocalDateTime().toString();

        Map<String, AttributeValue> keyMap = new HashMap<>();
        keyMap.put("PK", AttributeValue.builder().s(partitionKey).build());
        keyMap.put("SK", AttributeValue.builder().s("META").build());

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":increment", AttributeValue.builder().n("1").build());
        expressionAttributeValues.put(":max", AttributeValue.builder().n(String.valueOf(maxRequestsPerHour)).build());
        // Set TTL to 2 hours from the window start to auto-cleanup DynamoDB items
        long ttlSeconds = hourStart.plus(2, ChronoUnit.HOURS).getEpochSecond();
        expressionAttributeValues.put(":ttl", AttributeValue.builder().n(String.valueOf(ttlSeconds)).build());

        // Increment the request count if it's strictly less than the max.
        // If the item doesn't exist, attribute_not_exists evaluates to true, and it
        // initializes to 1.
        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(keyMap)
                .updateExpression("ADD requestCount :increment SET #ttl = :ttl")
                .conditionExpression("attribute_not_exists(requestCount) OR requestCount < :max")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(expressionAttributeValues)
                .build();

        try {
            dynamoDbClient.updateItem(updateRequest);
            log.debug("Rate limit consumed 1 token for key {} in window {}", key, hourStart);
        } catch (ConditionalCheckFailedException e) {
            log.warn("Rate limit exceeded for key: {}. Max allowed: {} per hour.", key, maxRequestsPerHour);
            throw new RateLimitExceededException(
                    "Rate limit exceeded. Maximum " + maxRequestsPerHour + " requests allowed per hour.");
        } catch (Exception e) {
            // For general DynamoDB errors, log and fail open (or closed, depending on
            // security posture).
            // For CI/CD agent, failing open on transient DynamoDB errors prevents blocking
            // valid work.
            log.error("Failed to update rate limit counter in DynamoDB for key {}. Failing open.", key, e);
        }
    }
}
