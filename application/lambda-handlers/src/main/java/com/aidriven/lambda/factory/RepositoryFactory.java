package com.aidriven.lambda.factory;

import com.aidriven.core.repository.GenerationMetricsRepository;
import com.aidriven.core.repository.TicketStateRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Factory for creating and caching repository instances.
 * Extracted from ServiceFactory to satisfy SRP - repositories are a distinct
 * concern from other service creations.
 */
public class RepositoryFactory {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final java.util.concurrent.ConcurrentHashMap<String, Object> cache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RepositoryFactory(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, java.util.function.Supplier<T> supplier) {
        return (T) cache.computeIfAbsent(key, k -> supplier.get());
    }

    public TicketStateRepository ticketStateRepository() {
        return cached("TicketStateRepository",
                () -> new TicketStateRepository(dynamoDbClient, tableName));
    }

    public GenerationMetricsRepository generationMetricsRepository() {
        return cached("GenerationMetricsRepository",
                () -> new GenerationMetricsRepository(dynamoDbClient, tableName));
    }
}
