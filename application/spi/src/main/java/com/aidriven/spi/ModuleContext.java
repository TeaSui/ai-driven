package com.aidriven.spi;

import java.util.Map;
import java.util.Optional;

/**
 * Context provided to modules during initialization.
 * Contains tenant-specific configuration and shared service references.
 *
 * <p>This is the only way modules receive configuration — they never
 * read environment variables directly. This enables multi-tenant isolation.</p>
 */
public interface ModuleContext {

    /**
     * Tenant identifier (e.g., "acme-corp", "startup-xyz").
     * Used for logging, metrics, and data isolation.
     */
    String tenantId();

    /**
     * Get a required configuration value.
     *
     * @param key Configuration key (e.g., "api.url", "auth.token")
     * @return The configuration value
     * @throws IllegalArgumentException if the key is not found
     */
    String getRequiredConfig(String key);

    /**
     * Get an optional configuration value.
     *
     * @param key Configuration key
     * @return Optional containing the value, or empty if not set
     */
    Optional<String> getConfig(String key);

    /**
     * Get a configuration value with a default fallback.
     *
     * @param key          Configuration key
     * @param defaultValue Fallback value
     * @return The configuration value or the default
     */
    default String getConfig(String key, String defaultValue) {
        return getConfig(key).orElse(defaultValue);
    }

    /**
     * Get an integer configuration value.
     */
    default int getIntConfig(String key, int defaultValue) {
        return getConfig(key).map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Get a boolean configuration value.
     */
    default boolean getBoolConfig(String key, boolean defaultValue) {
        return getConfig(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    /**
     * Get all configuration entries as a map.
     * Useful for passing bulk config to external SDKs.
     */
    Map<String, String> getAllConfig();

    /**
     * Resolve a secret by its key/ARN.
     * Delegates to the tenant's configured secrets provider.
     *
     * @param secretKey Secret identifier
     * @return The secret value
     */
    String resolveSecret(String secretKey);

    /**
     * Resolve a JSON secret and return as a map.
     */
    Map<String, Object> resolveSecretJson(String secretKey);
}
