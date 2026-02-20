package com.aidriven.spi;

import java.util.Map;

/**
 * Service Provider Interface for secrets/credentials management.
 * Implementations can wrap AWS Secrets Manager, HashiCorp Vault,
 * Azure Key Vault, GCP Secret Manager, etc.
 */
public interface SecretsProvider {

    /**
     * Unique identifier for this provider.
     */
    String providerId();

    /**
     * Retrieves a secret string by its identifier.
     */
    String getSecret(String secretId);

    /**
     * Retrieves a secret and parses it as key-value pairs.
     */
    Map<String, Object> getSecretJson(String secretId);
}
