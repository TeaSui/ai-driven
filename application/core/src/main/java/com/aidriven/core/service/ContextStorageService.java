package com.aidriven.core.service;

/**
 * Interface for storing and retrieving code context documents.
 */
public interface ContextStorageService {
    /**
     * Stores the context document for a specific ticket.
     * 
     * @return The identifier/key where the context is stored (e.g., S3 URL or Key).
     */
    String storeContext(String ticketKey, String contextDocument);

    /**
     * Retrieves the stored context document.
     */
    String getContext(String s3Key);
}
