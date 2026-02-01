package com.aidriven.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPathExtractorTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void should_extract_simple_string_value() throws Exception {
        JsonNode json = objectMapper.readTree("{\"name\": \"test\"}");

        String result = JsonPathExtractor.getRequiredString(json, "test", "name");

        assertEquals("test", result);
    }

    @Test
    void should_extract_nested_string_value() throws Exception {
        JsonNode json = objectMapper.readTree("{\"target\": {\"hash\": \"abc123\"}}");

        String result = JsonPathExtractor.getRequiredString(json, "Bitbucket branch", "target", "hash");

        assertEquals("abc123", result);
    }

    @Test
    void should_extract_deeply_nested_value() throws Exception {
        JsonNode json = objectMapper.readTree("{\"links\": {\"html\": {\"href\": \"https://example.com\"}}}");

        String result = JsonPathExtractor.getRequiredString(json, "PR response", "links", "html", "href");

        assertEquals("https://example.com", result);
    }

    @Test
    void should_extract_number_as_string() throws Exception {
        JsonNode json = objectMapper.readTree("{\"id\": 12345}");

        String result = JsonPathExtractor.getRequiredString(json, "PR", "id");

        assertEquals("12345", result);
    }

    @Test
    void should_throw_when_field_is_missing() throws Exception {
        JsonNode json = objectMapper.readTree("{\"name\": \"test\"}");

        JsonPathExtractor.JsonPathException exception = assertThrows(
                JsonPathExtractor.JsonPathException.class,
                () -> JsonPathExtractor.getRequiredString(json, "test", "missing"));

        assertTrue(exception.getMessage().contains("Missing required field 'missing'"));
        assertTrue(exception.getMessage().contains("test response"));
    }

    @Test
    void should_throw_when_nested_field_is_missing() throws Exception {
        JsonNode json = objectMapper.readTree("{\"target\": {}}");

        JsonPathExtractor.JsonPathException exception = assertThrows(
                JsonPathExtractor.JsonPathException.class,
                () -> JsonPathExtractor.getRequiredString(json, "branch", "target", "hash"));

        assertTrue(exception.getMessage().contains("Missing required field 'hash'"));
        assertTrue(exception.getMessage().contains("Full path: target.hash"));
    }

    @Test
    void should_throw_when_intermediate_field_is_null() throws Exception {
        JsonNode json = objectMapper.readTree("{\"target\": null}");

        JsonPathExtractor.JsonPathException exception = assertThrows(
                JsonPathExtractor.JsonPathException.class,
                () -> JsonPathExtractor.getRequiredString(json, "branch", "target", "hash"));

        assertTrue(exception.getMessage().contains("Missing required field 'target'"));
    }

    @Test
    void should_throw_when_root_is_null() {
        assertThrows(NullPointerException.class,
                () -> JsonPathExtractor.getRequiredString(null, "context", "field"));
    }

    @Test
    void should_throw_when_path_is_empty() throws Exception {
        JsonNode json = objectMapper.readTree("{\"name\": \"test\"}");

        assertThrows(IllegalArgumentException.class,
                () -> JsonPathExtractor.getRequiredString(json, "context"));
    }

    @Test
    void should_include_json_body_in_exception() throws Exception {
        String jsonString = "{\"data\": \"value\"}";
        JsonNode json = objectMapper.readTree(jsonString);

        JsonPathExtractor.JsonPathException exception = assertThrows(
                JsonPathExtractor.JsonPathException.class,
                () -> JsonPathExtractor.getRequiredString(json, "test", "missing"));

        // Jackson may reformat the JSON (e.g., remove spaces), so check content equivalence
        assertTrue(exception.getJsonBody().contains("\"data\""));
        assertTrue(exception.getJsonBody().contains("\"value\""));
    }

    @Test
    void should_truncate_long_json_in_toString() throws Exception {
        StringBuilder longJson = new StringBuilder("{\"data\": \"");
        for (int i = 0; i < 1000; i++) {
            longJson.append("x");
        }
        longJson.append("\"}");

        JsonNode json = objectMapper.readTree(longJson.toString());

        JsonPathExtractor.JsonPathException exception = assertThrows(
                JsonPathExtractor.JsonPathException.class,
                () -> JsonPathExtractor.getRequiredString(json, "test", "missing"));

        String result = exception.toString();
        // toString wraps truncated body in parentheses: "message (Response body: ...truncated...)"
        assertTrue(result.contains("..."), "Expected truncation marker in: " + result);
    }

    // Optional string tests
    @Test
    void should_return_optional_value_when_present() throws Exception {
        JsonNode json = objectMapper.readTree("{\"name\": \"test\"}");

        Optional<String> result = JsonPathExtractor.getOptionalString(json, "name");

        assertTrue(result.isPresent());
        assertEquals("test", result.get());
    }

    @Test
    void should_return_empty_optional_when_field_missing() throws Exception {
        JsonNode json = objectMapper.readTree("{\"name\": \"test\"}");

        Optional<String> result = JsonPathExtractor.getOptionalString(json, "missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void should_return_empty_optional_when_nested_field_missing() throws Exception {
        JsonNode json = objectMapper.readTree("{\"target\": {}}");

        Optional<String> result = JsonPathExtractor.getOptionalString(json, "target", "hash");

        assertTrue(result.isEmpty());
    }

    @Test
    void should_return_empty_optional_when_root_is_null() {
        Optional<String> result = JsonPathExtractor.getOptionalString(null, "field");

        assertTrue(result.isEmpty());
    }

    @Test
    void should_return_empty_optional_when_path_is_empty() throws Exception {
        JsonNode json = objectMapper.readTree("{\"name\": \"test\"}");

        Optional<String> result = JsonPathExtractor.getOptionalString(json);

        assertTrue(result.isEmpty());
    }

    // Integer extraction tests
    @Test
    void should_extract_integer_value() throws Exception {
        JsonNode json = objectMapper.readTree("{\"count\": 42}");

        int result = JsonPathExtractor.getRequiredInt(json, "test", "count");

        assertEquals(42, result);
    }

    @Test
    void should_extract_integer_from_string() throws Exception {
        JsonNode json = objectMapper.readTree("{\"count\": \"123\"}");

        int result = JsonPathExtractor.getRequiredInt(json, "test", "count");

        assertEquals(123, result);
    }

    @Test
    void should_throw_when_value_is_not_integer() throws Exception {
        JsonNode json = objectMapper.readTree("{\"value\": \"not-a-number\"}");

        JsonPathExtractor.JsonPathException exception = assertThrows(
                JsonPathExtractor.JsonPathException.class,
                () -> JsonPathExtractor.getRequiredInt(json, "test", "value"));

        assertTrue(exception.getMessage().contains("Expected integer"));
    }
}
