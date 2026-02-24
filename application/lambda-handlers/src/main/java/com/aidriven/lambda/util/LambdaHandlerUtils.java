package com.aidriven.lambda.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.lambda.powertools.logging.LoggingUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for common Lambda handler operations.
 * Provides shared methods for response building, error handling, and logging.
 */
@Slf4j
public class LambdaHandlerUtils {

    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_INTERNAL_ERROR = 500;

    private LambdaHandlerUtils() {
        // Utility class, no instantiation
    }

    /**
     * Creates an HTTP response with the specified status code and body.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body (will be converted to JSON)
     * @return a map representing the Lambda response
     */
    public static Map<String, Object> createHttpResponse(int statusCode, Map<String, Object> body) {
        return Map.of(
                "statusCode", statusCode,
                "body", toJson(body),
                "headers", Map.of("Content-Type", "application/json"));
    }

    /**
     * Creates a successful (200) HTTP response.
     *
     * @param body the response body
     * @return a map representing the Lambda response
     */
    public static Map<String, Object> createSuccessResponse(Map<String, Object> body) {
        return createHttpResponse(HTTP_OK, body);
    }

    /**
     * Creates a bad request (400) HTTP response.
     *
     * @param message the error message
     * @return a map representing the Lambda response
     */
    public static Map<String, Object> createBadRequestResponse(String message) {
        return createHttpResponse(HTTP_BAD_REQUEST, Map.of("error", message));
    }

    /**
     * Creates an error (500) HTTP response.
     *
     * @param message the error message
     * @return a map representing the Lambda response
     */
    public static Map<String, Object> createErrorResponse(String message) {
        return createHttpResponse(HTTP_INTERNAL_ERROR, Map.of("error", message));
    }

    /**
     * Appends a key-value pair to Lambda logging context.
     *
     * @param key   the key
     * @param value the value
     */
    public static void appendLoggingContext(String key, Object value) {
        try {
            LoggingUtils.appendKey(key, String.valueOf(value));
        } catch (Exception e) {
            log.warn("Failed to append logging context for key: {}", key, e);
        }
    }

    /**
     * Appends multiple key-value pairs to Lambda logging context.
     *
     * @param contextMap the map of key-value pairs to append
     */
    public static void appendLoggingContext(Map<String, Object> contextMap) {
        contextMap.forEach(LambdaHandlerUtils::appendLoggingContext);
    }

    /**
     * Converts an object to JSON string.
     *
     * @param object the object to convert
     * @return the JSON string representation
     */
    public static String toJson(Object object) {
        try {
            return new ObjectMapper().writeValueAsString(object);
        } catch (Exception e) {
            log.error("Failed to serialize object to JSON", e);
            return "{}";
        }
    }

    /**
     * Builds a structured log context for a Lambda handler.
     *
     * @param ticketKey     the ticket key
     * @param correlationId the correlation ID (AWS Request ID)
     * @return a map with logging context
     */
    public static Map<String, Object> buildLoggingContext(String ticketKey, String correlationId) {
        return Map.of(
                "ticketKey", ticketKey != null ? ticketKey : "N/A",
                "correlationId", correlationId != null ? correlationId : "N/A");
    }

    /**
     * Logs an error and returns an error response.
     *
     * @param message the error message
     * @param cause   the exception cause
     * @return an error response map
     */
    public static Map<String, Object> handleError(String message, Exception cause) {
        log.error(message, cause);
        return createErrorResponse(message);
    }

    /**
     * Safe type conversion from Map with default value.
     *
     * @param map          the map to read from
     * @param key          the key to look up
     * @param defaultValue the default value if key not found or null
     * @param type         the expected type
     * @param <T>          the generic type parameter
     * @return the value cast to the expected type, or defaultValue
     */
    @SuppressWarnings("unchecked")
    public static <T> T getSafeValue(Map<String, Object> map, String key, T defaultValue, Class<T> type) {
        if (map == null || !map.containsKey(key)) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            log.warn("Failed to cast value for key '{}' to type {}", key, type.getSimpleName(), e);
            return defaultValue;
        }
    }

    /**
     * Safely extracts a string value from a map with default value.
     *
     * @param map          the map to read from
     * @param key          the key to look up
     * @param defaultValue the default value if key not found or null
     * @return the string value or defaultValue
     */
    public static String getSafeString(Map<String, Object> map, String key, String defaultValue) {
        return getSafeValue(map, key, defaultValue, String.class);
    }

    /**
     * Safely extracts a boolean value from a map with default value.
     *
     * @param map          the map to read from
     * @param key          the key to look up
     * @param defaultValue the default value if key not found or null
     * @return the boolean value or defaultValue
     */
    public static boolean getSafeBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Boolean result = getSafeValue(map, key, defaultValue ? Boolean.TRUE : Boolean.FALSE, Boolean.class);
        return result != null && result;
    }

    /**
     * Builds a base output map for Lambda handlers.
     *
     * @param input the input map from the Lambda event
     * @return a new map with copied relevant fields
     */
    public static Map<String, Object> buildBaseOutput(Map<String, Object> input) {
        Map<String, Object> output = new HashMap<>();
        String ticketId = getSafeString(input, "ticketId", null);
        String ticketKey = getSafeString(input, "ticketKey", null);

        if (ticketId != null) {
            output.put("ticketId", ticketId);
        }
        if (ticketKey != null) {
            output.put("ticketKey", ticketKey);
        }

        return output;
    }
}
