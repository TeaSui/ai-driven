package com.aidriven.core.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Utility for safe extraction of values from nested JSON structures.
 * Provides meaningful error messages when required fields are missing.
 */
public final class JsonPathExtractor {

    private JsonPathExtractor() {
        // Utility class
    }

    /**
     * Extracts a required string value from a nested JSON path.
     *
     * @param root The root JSON node
     * @param context Description of what is being parsed (for error messages)
     * @param path The path segments to navigate (e.g., "target", "hash")
     * @return The string value at the path
     * @throws JsonPathException if any node in the path is missing or null
     */
    public static String getRequiredString(JsonNode root, String context, String... path) {
        Objects.requireNonNull(root, "JSON root must not be null");
        Objects.requireNonNull(path, "path must not be null");

        if (path.length == 0) {
            throw new IllegalArgumentException("path must not be empty");
        }

        JsonNode current = root;
        StringBuilder traversedPath = new StringBuilder();

        for (int i = 0; i < path.length; i++) {
            String segment = path[i];
            if (i > 0) {
                traversedPath.append(".");
            }
            traversedPath.append(segment);

            JsonNode next = current.get(segment);
            if (next == null || next.isNull()) {
                throw new JsonPathException(
                        String.format("Missing required field '%s' in %s response. Full path: %s",
                                segment, context, traversedPath),
                        root.toString());
            }
            current = next;
        }

        if (!current.isTextual() && !current.isNumber()) {
            throw new JsonPathException(
                    String.format("Expected string or number at path '%s' in %s response, but got: %s",
                            traversedPath, context, current.getNodeType()),
                    root.toString());
        }

        return current.asText();
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

        JsonNode current = root;
        for (String segment : path) {
            JsonNode next = current.get(segment);
            if (next == null || next.isNull()) {
                return Optional.empty();
            }
            current = next;
        }

        if (current.isTextual() || current.isNumber()) {
            return Optional.of(current.asText());
        }
        return Optional.empty();
    }

    /**
     * Extracts a required integer value from a nested JSON path.
     *
     * @param root The root JSON node
     * @param context Description of what is being parsed (for error messages)
     * @param path The path segments to navigate
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
