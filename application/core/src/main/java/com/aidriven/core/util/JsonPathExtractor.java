package com.aidriven.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import java.util.Objects;
import java.util.Optional;

/**
 * Utility for safe extraction of values from nested JSON structures using
 * Jayway JsonPath.
 */
public final class JsonPathExtractor {

    private JsonPathExtractor() {
        // Utility class
    }

    /**
     * Extracts a required string value from a nested JSON path.
     *
     * @param root    The root JSON node
     * @param context Description of what is being parsed (for error messages)
     * @param path    The path segments to navigate (e.g., "target", "hash")
     * @return The string value at the path
     * @throws JsonPathException if any node in the path is missing or null
     */
    public static String getRequiredString(JsonNode root, String context, String... path) {
        Objects.requireNonNull(root, "JSON root must not be null");
        Objects.requireNonNull(path, "path must not be null");

        if (path.length == 0) {
            throw new IllegalArgumentException("path must not be empty");
        }

        String expression = "$." + String.join(".", path);
        try {
            Object result = JsonPath.read(root.toString(), expression);
            if (result == null) {
                throw new JsonPathException(
                        String.format("Missing required field '%s' in %s response. Full path: %s",
                                path[path.length - 1], context, String.join(".", path)),
                        root.toString());
            }
            return String.valueOf(result);
        } catch (PathNotFoundException e) {
            // Find which part of the path is missing for accurate error message
            String currentPath = "$";
            for (String segment : path) {
                try {
                    Object val = JsonPath.read(root.toString(), currentPath + "." + segment);
                    if (val == null) {
                        throw new JsonPathException(
                                String.format("Missing required field '%s' in %s response. Full path: %s",
                                        segment, context, String.join(".", path)),
                                root.toString());
                    }
                    currentPath += "." + segment;
                } catch (PathNotFoundException ex) {
                    throw new JsonPathException(
                            String.format("Missing required field '%s' in %s response. Full path: %s",
                                    segment, context, String.join(".", path)),
                            root.toString());
                }
            }
            throw new JsonPathException(
                    String.format("Missing required field in %s response at path: %s", context, expression),
                    root.toString());
        }
    }

    /**
     * Extracts an optional string value from a nested JSON path.
     *
     * @param root The root JSON node
     * @param path The path segments to navigate
     * @return Optional containing the value if present, empty otherwise
     */
    public static Optional<String> getOptionalString(JsonNode root, String... path) {
        if (root == null || path == null || path.length == 0) {
            return Optional.empty();
        }

        String expression = "$." + String.join(".", path);
        try {
            Object result = JsonPath.read(root.toString(), expression);
            return Optional.ofNullable(result).map(String::valueOf);
        } catch (PathNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Extracts a required integer value from a nested JSON path.
     *
     * @param root    The root JSON node
     * @param context Description of what is being parsed (for error messages)
     * @param path    The path segments to navigate
     * @return The integer value at the path
     * @throws JsonPathException if any node in the path is missing or not a number
     */
    public static int getRequiredInt(JsonNode root, String context, String... path) {
        String value = getRequiredString(root, context, path);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new JsonPathException(
                    String.format("Expected integer at path '%s' in %s response, but got: %s",
                            String.join(".", path), context, value),
                    root.toString());
        }
    }

    /**
     * Exception thrown when JSON path extraction fails.
     */
    public static class JsonPathException extends RuntimeException {

        private final String jsonBody;

        public JsonPathException(String message, String jsonBody) {
            super(message);
            this.jsonBody = jsonBody;
        }

        public String getJsonBody() {
            return jsonBody;
        }

        @Override
        public String toString() {
            String truncatedBody = jsonBody != null && jsonBody.length() > 500
                    ? jsonBody.substring(0, 500) + "..."
                    : jsonBody;
            return String.format("%s (Response body: %s)", getMessage(), truncatedBody);
        }
    }
}
