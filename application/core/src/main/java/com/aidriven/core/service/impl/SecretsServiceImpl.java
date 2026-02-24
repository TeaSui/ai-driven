package com.aidriven.core.service.impl;

import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for retrieving secrets from AWS Secrets Manager.
 * Includes caching to reduce API calls.
 */
@Slf4j
@RequiredArgsConstructor
public class SecretsServiceImpl implements SecretsService {

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();
    private final long cacheTtlMillis = 5 * 60 * 1000; // 5 minutes

    /**
     * Gets a secret value as a string.
     */
    @Override
    public String getSecret(String secretArn) {

        CachedSecret cached = cache.get(secretArn);

        if (cached != null && !cached.isExpired()) {
            log.debug("Returning cached secret for: {}", secretArn);
            return cached.value;
        }

        log.info("Fetching secret from Secrets Manager: {}", secretArn);

        GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                GetSecretValueRequest.builder()
                        .secretId(secretArn)
                        .build());

        String secretValue = response.secretString();
        cache.put(secretArn, new CachedSecret(secretValue, System.currentTimeMillis() + cacheTtlMillis));

        return secretValue;
    }

    /**
     * Gets a secret value as a JSON object.
     */
    @Override
    public Map<String, Object> getSecretJson(String secretArn) {
        try {
            String secretString = getSecret(secretArn);
            return objectMapper.readValue(secretString, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            log.error("Failed to parse secret JSON for {}: {}", secretArn, e.getMessage());
            throw new RuntimeException("Failed to parse secret JSON", e);
        }
    }

    /**
     * Retrieves a secret and parses it directly into the specified class.
     */
    @Override
    public <T> T getSecretAs(String secretArn, Class<T> clazz) {
        try {
            String secretString = getSecret(secretArn);
            return objectMapper.readValue(secretString, clazz);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse secret to {} for {}: {}", clazz.getSimpleName(), secretArn, e.getMessage());
            throw new RuntimeException("Failed to parse secret JSON to POJO", e);
        }
    }

    /**
     * Gets a specific field from a JSON secret.
     */
    public String getSecretField(String secretArn, String fieldName) {
        Map<String, Object> json = getSecretJson(secretArn);
        Object field = json.get(fieldName);
        return field != null ? field.toString() : null;
    }

    /**
     * Clears the cache for a specific secret.
     */
    public void invalidateCache(String secretArn) {
        cache.remove(secretArn);
    }

    /**
     * Clears all cached secrets.
     */
    public void clearCache() {
        cache.clear();
    }

    private static class CachedSecret {
        final String value;
        final long expiresAt;

        CachedSecret(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
