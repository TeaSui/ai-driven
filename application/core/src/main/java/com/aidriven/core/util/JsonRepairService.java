package com.aidriven.core.util;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Shared utility for parsing and repairing malformed / truncated JSON.
 *
 * <p>Claude API responses may be truncated mid-stream when the output exceeds
 * the context window. This service applies multiple repair strategies:
 * <ol>
 *   <li>Direct parse (fast path for well-formed JSON)</li>
 *   <li>Closure repair: close any open JSON constructs (strings, arrays, objects)</li>
 *   <li>Truncation repair: find the error offset, scan backwards for a safe
 *       truncation point, close the remaining constructs</li>
 * </ol>
 *
 * <p>Thread-safe as long as the supplied {@link ObjectMapper} is thread-safe
 * (the default Jackson ObjectMapper is safe for read operations).
 */
@Slf4j
public class JsonRepairService {

    /** Minimum character offset of the JSON error before truncation repair is attempted. */
    static final int MIN_ERROR_POSITION = 100;

    /** Maximum number of characters to scan backward from the error position. */
    static final int MAX_BACKWARD_SEARCH_OFFSET = 50;

    /** Maximum window (in characters) from the error position to search for truncation points. */
    static final int MAX_TRUNCATION_SEARCH_WINDOW = 2000;

    private final ObjectMapper objectMapper;

    public JsonRepairService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Attempts to parse JSON with multiple repair strategies for handling
     * malformed JSON from auto-continuation stitching.
     *
     * @param jsonStr the raw JSON string (may be truncated or malformed)
     * @return the parsed {@link JsonNode}, or {@code null} if all strategies fail
     */
    public JsonNode parseJsonWithRepair(String jsonStr) {
        // Strategy 1: Direct parse
        try {
            return objectMapper.readTree(jsonStr);
        } catch (Exception e) {
            log.warn("Direct JSON parse failed: {}", e.getMessage());
        }

        // Strategy 2: Close any open JSON constructs (handles truncation)
        try {
            String closed = closeJsonString(jsonStr);
            JsonNode node = objectMapper.readTree(closed);
            if (node.has("files")) {
                log.info("JSON repair succeeded: closure strategy");
                return node;
            }
        } catch (Exception e) {
            log.warn("Closure repair failed: {}", e.getMessage());
        }

        // Strategy 3: Find error position, truncate before it, close
        int errorPos = findJsonErrorPosition(jsonStr);
        if (errorPos > MIN_ERROR_POSITION) {
            log.info("JSON error at char offset {}, attempting truncation repair", errorPos);
            int lowerBound = Math.max(MAX_BACKWARD_SEARCH_OFFSET, errorPos - MAX_TRUNCATION_SEARCH_WINDOW);
            for (int i = errorPos - 1; i > lowerBound; i--) {
                char c = jsonStr.charAt(i);
                if (c == '}' || c == ']' || c == '"' || c == ',') {
                    String prefix = jsonStr.substring(0, i + 1);
                    String closed = closeJsonString(prefix);
                    try {
                        JsonNode node = objectMapper.readTree(closed);
                        if (node.has("files")) {
                            log.info("JSON repair succeeded: truncation at {} (error at {}), preserved {}/{} chars",
                                    i, errorPos, i, jsonStr.length());
                            return node;
                        }
                    } catch (Exception ignored) {
                        // Try next position
                    }
                }
            }
        }

        log.error("All JSON repair strategies exhausted");
        return null;
    }

    /**
     * Finds the character offset where JSON parsing fails.
     *
     * @param jsonStr the JSON string to validate
     * @return the character offset of the first parse error, or {@code -1} if the JSON is valid
     */
    public int findJsonErrorPosition(String jsonStr) {
        try (com.fasterxml.jackson.core.JsonParser parser = objectMapper.getFactory().createParser(jsonStr)) {
            while (parser.nextToken() != null) {
                // consume all tokens
            }
        } catch (JsonParseException e) {
            return (int) e.getLocation().getCharOffset();
        } catch (Exception e) {
            // other error
        }
        return -1;
    }

    /**
     * Closes any open JSON constructs (strings, arrays, objects) to make
     * truncated JSON parseable. Uses a stack-based approach to track nesting.
     *
     * @param partial the partial / truncated JSON string
     * @return the input with appropriate closing tokens appended
     */
    public String closeJsonString(String partial) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < partial.length(); i++) {
            char c = partial.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                    stack.pop();
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    stack.push('"');
                    break;
                case '{':
                    stack.push('{');
                    break;
                case '[':
                    stack.push('[');
                    break;
                case '}':
                    if (!stack.isEmpty() && stack.peek() == '{')
                        stack.pop();
                    break;
                case ']':
                    if (!stack.isEmpty() && stack.peek() == '[')
                        stack.pop();
                    break;
                default:
                    break;
            }
        }

        // Trim trailing commas/whitespace if not inside a string
        String base = partial;
        if (!inString) {
            int end = base.length() - 1;
            while (end >= 0) {
                char c = base.charAt(end);
                if (c == ',' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    end--;
                } else {
                    break;
                }
            }
            base = base.substring(0, end + 1);
        }

        StringBuilder sb = new StringBuilder(base);
        while (!stack.isEmpty()) {
            char open = stack.pop();
            switch (open) {
                case '"':
                    sb.append('"');
                    break;
                case '{':
                    sb.append('}');
                    break;
                case '[':
                    sb.append(']');
                    break;
                default:
                    break;
            }
        }
        return sb.toString();
    }
}
