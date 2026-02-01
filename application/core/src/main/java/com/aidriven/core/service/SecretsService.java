package com.aidriven.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class SecretsService {
    
    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedSecret> cache;
    private final long cacheTtlMillis;
    
    private static final long DEFAULT_CACHE_TTL_MILLIS = 5 * 60 * 1000; // 5 minutes
    
    public SecretsService(SecretsManagerClient secretsManagerClient) {
        this(secretsManagerClient, DEFAULT_CACHE_TTL_MILLIS);
    }
    
    public SecretsService(SecretsManagerClient secretsManagerClient, long cacheTtlMillis) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
        this.cache = new ConcurrentHashMap<>();
        this.cacheTtlMillis = cacheTtlMillis;
    }
    
    /**
     * Gets a secret value as a string.
     */
    public String getSecretString(String secretArn) {
        CachedSecret cached = cache.get(secretArn);
        
        if (cached != null && !cached.isExpired()) {
            log.debug("Returning cached secret for: {}", secretArn);
            return cached.value;
        }
        
        log.info("Fetching secret from Secrets Manager: {}", secretArn);
        
        GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                GetSecretValueRequest.builder()
                        .secretId(secretArn)
                        .build()
        );
        
        String secretValue = response.secretString();
        cache.put(secretArn, new CachedSecret(secretValue, System.currentTimeMillis() + cacheTtlMillis));
        
        return secretValue;
    }
    
    /**
     * Gets a secret value as a JSON object.
     */
    public JsonNode getSecretJson(String secretArn) throws JsonProcessingException {
        String secretString = getSecretString(secretArn);
        return objectMapper.readTree(secretString);
    }
    
    /**
     * Gets a specific field from a JSON secret.
     */
    public String getSecretField(String secretArn, String fieldName) throws JsonProcessingException {
        JsonNode json = getSecretJson(secretArn);
        JsonNode field = json.get(fieldName);
        return field != null ? field.asText() : null;
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
