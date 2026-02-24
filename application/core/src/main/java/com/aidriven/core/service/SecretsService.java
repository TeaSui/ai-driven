package com.aidriven.core.service;

import java.util.Map;

/**
 * Interface for retrieving secrets from an external provider (e.g., AWS Secrets
 * Manager).
 */
public interface SecretsService {
    /**
     * Retrieves a secret string by its ARN or name.
     */
    String getSecret(String secretArn);

    /**
     * Retrieves a secret and parses it as a Map of key-value pairs.
     */
    Map<String, Object> getSecretJson(String secretArn);

    /**
     * Retrieves a secret and parses it directly into the specified class.
     */
    <T> T getSecretAs(String secretArn, Class<T> clazz);
}
