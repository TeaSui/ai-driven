package com.aidriven.spi;

import java.util.Map;
import java.util.Optional;

/**
 * Service Provider Interface for state/key-value storage.
 * Implementations can wrap DynamoDB, Redis, PostgreSQL, etc.
 */
public interface StateStoreProvider {

    /**
     * Unique identifier for this provider.
     */
    String providerId();

    /**
     * Saves a state record.
     */
    void save(String partitionKey, String sortKey, Map<String, Object> attributes);

    /**
     * Saves a record only if it doesn't already exist (for idempotency).
     *
     * @return true if saved, false if already exists
     */
    boolean saveIfNotExists(String partitionKey, String sortKey, Map<String, Object> attributes);

    /**
     * Gets a record by its keys.
     */
    Optional<Map<String, Object>> get(String partitionKey, String sortKey);

    /**
     * Deletes a record.
     */
    void delete(String partitionKey, String sortKey);

    /**
     * Queries records by partition key with optional sort key prefix.
     */
    java.util.List<Map<String, Object>> query(String partitionKey, String sortKeyPrefix);
}
