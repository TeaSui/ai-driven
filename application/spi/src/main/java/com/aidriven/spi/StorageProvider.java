package com.aidriven.spi;

/**
 * Service Provider Interface for object/blob storage.
 * Implementations can wrap AWS S3, Azure Blob Storage, GCP Cloud Storage, etc.
 */
public interface StorageProvider {

    /**
     * Unique identifier for this provider.
     */
    String providerId();

    /**
     * Stores content and returns a key/identifier.
     */
    String store(String namespace, String key, String content);

    /**
     * Retrieves content by key.
     */
    String retrieve(String key);

    /**
     * Deletes content by key.
     */
    void delete(String key);

    /**
     * Checks if content exists at the given key.
     */
    boolean exists(String key);
}
