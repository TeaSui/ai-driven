package com.aidriven.spi;

import java.util.Map;
import java.util.Optional;

/**
 * Context provided to modules during initialization.
 * Contains tenant-specific configuration, secrets, and shared infrastructure.
 *
 * <p>This decouples modules from AWS-specific services and AppConfig singleton,
 * making them testable and portable.</p>
 */
public interface ModuleContext {

    /**
     * Returns a configuration value by key.
     *
     * @param key Configuration key (e.g., "baseUrl", "model")
     * @return The value, or empty if not configured
     */
    Optional<String> getConfig(String key);

    /**
     * Returns a configuration value with a default fallback.
     */
    default String getConfig(String key, String defaultValue) {
        return getConfig(key).orElse(defaultValue);
    }

    /**
     * Returns an integer configuration value.
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
     * Returns a secret value by key.
     * Secrets are resolved from the tenant's secret store (e.g., AWS Secrets Manager).
     *
     * @param key Secret key (e.g., "apiToken", "appPassword")
     * @return The secret value
     * @throws IllegalStateException if the secret is not available
     */
    String getSecret(String key);

    /**
     * Returns all secrets as a map.
     */
    Map<String, String> getSecrets();

    /**
     * Returns the tenant identifier.
     */
    String tenantId();

    /**
     * Returns the module-specific configuration as a flat map.
     */
    Map<String, String> getAllConfig();
}
