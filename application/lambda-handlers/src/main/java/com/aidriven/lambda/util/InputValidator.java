package com.aidriven.lambda.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Utility for validating and extracting Lambda handler input parameters.
 * Provides consistent error handling and type-safe parameter extraction.
 */
@Slf4j
public class InputValidator {

    private static final String MISSING_PARAMETER_MESSAGE = "Missing required parameter: %s";

    private InputValidator() {
        // Utility class, no instantiation
    }

    /**
     * Validates that all required parameters are present in the input map.
     *
     * @param input              the input map
     * @param requiredParameters the required parameter names
     * @throws com.aidriven.spi.exception.ValidationException if any required
     *                                                        parameter is missing
     */
    public static void validateRequired(Map<String, Object> input, String... requiredParameters) {
        if (input == null) {
            throw new com.aidriven.spi.exception.ValidationException("Input map cannot be null");
        }

        for (String param : requiredParameters) {
            if (!input.containsKey(param) || input.get(param) == null) {
                throw new com.aidriven.spi.exception.ValidationException(
                        String.format(MISSING_PARAMETER_MESSAGE, param));
            }
        }
    }

    /**
     * Extracts a required string parameter.
     *
     * @param input         the input map
     * @param parameterName the parameter name
     * @return the parameter value
     * @throws com.aidriven.spi.exception.ValidationException if the parameter is
     *                                                        missing or null
     */
    public static String extractRequiredString(Map<String, Object> input, String parameterName) {
        validateRequired(input, parameterName);
        Object value = input.get(parameterName);
        if (!(value instanceof String)) {
            throw new com.aidriven.spi.exception.ValidationException(
                    String.format("Parameter '%s' must be a string, got: %s", parameterName,
                            value.getClass().getSimpleName()));
        }
        return (String) value;
    }

    /**
     * Extracts an optional string parameter with a default value.
     *
     * @param input         the input map
     * @param parameterName the parameter name
     * @param defaultValue  the default value if not found
     * @return the parameter value or default
     */
    public static String extractOptionalString(Map<String, Object> input, String parameterName, String defaultValue) {
        if (input == null || !input.containsKey(parameterName)) {
            return defaultValue;
        }
        Object value = input.get(parameterName);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String)) {
            log.warn("Parameter '{}' expected to be String but got {}, using default",
                    parameterName, value.getClass().getSimpleName());
            return defaultValue;
        }
        return (String) value;
    }

    /**
     * Extracts a required boolean parameter.
     *
     * @param input         the input map
     * @param parameterName the parameter name
     * @return the parameter value
     * @throws com.aidriven.spi.exception.ValidationException if the parameter is
     *                                                        missing
     */
    public static boolean extractRequiredBoolean(Map<String, Object> input, String parameterName) {
        validateRequired(input, parameterName);
        Object value = input.get(parameterName);
        if (!(value instanceof Boolean)) {
            throw new com.aidriven.spi.exception.ValidationException(
                    String.format("Parameter '%s' must be a boolean, got: %s", parameterName,
                            value.getClass().getSimpleName()));
        }
        return (Boolean) value;
    }

    /**
     * Extracts an optional boolean parameter with a default value.
     *
     * @param input         the input map
     * @param parameterName the parameter name
     * @param defaultValue  the default value if not found
     * @return the parameter value or default
     */
    public static boolean extractOptionalBoolean(Map<String, Object> input, String parameterName,
            boolean defaultValue) {
        if (input == null || !input.containsKey(parameterName)) {
            return defaultValue;
        }
        Object value = input.get(parameterName);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        log.warn("Parameter '{}' expected to be Boolean but got {}, using default",
                parameterName, value.getClass().getSimpleName());
        return defaultValue;
    }

    /**
     * Validates that the input map is not null or empty.
     *
     * @param input the input map
     * @throws com.aidriven.spi.exception.ValidationException if input is null or
     *                                                        empty
     */
    public static void validateNotEmpty(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            throw new com.aidriven.spi.exception.ValidationException("Input map cannot be null or empty");
        }
    }

    /**
     * Safely extracts an enum value from a string parameter.
     *
     * @param input         the input map
     * @param parameterName the parameter name
     * @param enumType      the enum class
     * @param <E>           the enum type
     * @return the parsed enum value
     * @throws com.aidriven.spi.exception.ValidationException if the parameter
     *                                                        cannot be parsed
     */
    public static <E extends Enum<E>> E extractRequiredEnum(Map<String, Object> input, String parameterName,
            Class<E> enumType) {
        String value = extractRequiredString(input, parameterName);
        try {
            return Enum.valueOf(enumType, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.aidriven.spi.exception.ValidationException(
                    String.format("Invalid value for parameter '%s': %s", parameterName, value), e);
        }
    }

    /**
     * Safely extracts an optional enum value from a string parameter.
     *
     * @param input         the input map
     * @param parameterName the parameter name
     * @param enumType      the enum class
     * @param defaultValue  the default value if not found
     * @param <E>           the enum type
     * @return the parsed enum value or default
     */
    public static <E extends Enum<E>> E extractOptionalEnum(Map<String, Object> input, String parameterName,
            Class<E> enumType, E defaultValue) {
        String value = extractOptionalString(input, parameterName, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumType, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid enum value for parameter '{}': {}, using default", parameterName, value);
            return defaultValue;
        }
    }

    /**
     * Validates that two values are not both null.
     *
     * @param value1    the first value
     * @param value2    the second value
     * @param paramName the parameter name for error message
     * @throws com.aidriven.spi.exception.ValidationException if both are null
     */
    public static void validateAtLeastOne(Object value1, Object value2, String paramName) {
        if (value1 == null && value2 == null) {
            throw new com.aidriven.spi.exception.ValidationException(
                    String.format("At least one of the values for '%s' must be provided", paramName));
        }
    }

    /**
     * Validates that a value is not null or blank (for strings).
     *
     * @param value     the value to check
     * @param paramName the parameter name for error message
     * @throws com.aidriven.spi.exception.ValidationException if the value is null
     *                                                        or blank
     */
    public static void validateNotBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new com.aidriven.spi.exception.ValidationException(
                    String.format("Parameter '%s' cannot be blank", paramName));
        }
    }
}
