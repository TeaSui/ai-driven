package com.aidriven.core.util;

import java.util.Map;
import java.util.Objects;

/**
 * Shared input validation utility for Lambda handlers.
 * Provides fail-fast validation of required input fields and environment variables.
 */
public final class LambdaInputValidator {

    private LambdaInputValidator() {
        // Utility class
    }

    /**
     * Extracts and validates a required string field from the input map.
     *
     * @param input     The Lambda input map
     * @param fieldName The field name to extract
     * @return The non-null, non-blank string value
     * @throws IllegalArgumentException if the field is missing, null, or blank
     */
    public static String requireString(Map<String, Object> input, String fieldName) {
        Object value = input.get(fieldName);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        String str = value.toString();
        if (str.isBlank()) {
            throw new IllegalArgumentException("Required field is blank: " + fieldName);
        }
        return str;
    }

    /**
     * Extracts an optional string field from the input map, returning a default if missing.
     *
     * @param input        The Lambda input map
     * @param fieldName    The field name to extract
     * @param defaultValue The default value if missing or null
     * @return The string value or the default
     */
    public static String optionalString(Map<String, Object> input, String fieldName, String defaultValue) {
        Object value = input.get(fieldName);
        if (value == null) {
            return defaultValue;
        }
        String str = value.toString();
        return str.isBlank() ? defaultValue : str;
    }

    /**
     * Validates that a required environment variable is set and non-blank.
     *
     * @param name The environment variable name
     * @return The non-null, non-blank value
     * @throws IllegalStateException if the variable is not set or blank
     */
    public static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required environment variable not set: " + name
                            + ". Check Lambda configuration.");
        }
        return value;
    }

    /**
     * Validates the input map is not null or empty.
     *
     * @param input   The Lambda input map
     * @param handler The handler name for error messages
     * @throws IllegalArgumentException if input is null or empty
     */
    public static void requireNonEmptyInput(Map<String, Object> input, String handler) {
        Objects.requireNonNull(input, handler + ": input must not be null");
        if (input.isEmpty()) {
            throw new IllegalArgumentException(handler + ": input must not be empty");
        }
    }
}
