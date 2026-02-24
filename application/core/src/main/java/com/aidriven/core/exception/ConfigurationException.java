package com.aidriven.core.exception;

/**
 * Thrown when application configuration is missing, invalid, or inconsistent.
 *
 * <p>
 * Typical causes:
 * <ul>
 * <li>A required environment variable is absent</li>
 * <li>A required field is missing from a Secrets Manager secret</li>
 * <li>An invalid value is provided for a configuration parameter</li>
 * </ul>
 */
public class ConfigurationException extends RuntimeException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
