package com.aidriven.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonRepairServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonRepairService service;

    @BeforeEach
    void setUp() {
        service = new JsonRepairService(objectMapper);
    }

    // --- parseJsonWithRepair tests ---

    @Test
    void should_parse_valid_json() {
        String validJson = "{\"files\": [{\"path\": \"src/Main.java\", \"content\": \"class Main {}\"}], \"commitMessage\": \"feat: init\"}";

        JsonNode result = service.parseJsonWithRepair(validJson);

        assertNotNull(result);
        assertTrue(result.has("files"));
        assertEquals(1, result.get("files").size());
        assertEquals("src/Main.java", result.get("files").get(0).get("path").asText());
    }

    @Test
    void should_repair_truncated_json() {
        // JSON missing closing braces -- cut off after content value
        String truncated = "{\"files\": [{\"path\": \"src/Main.java\", \"content\": \"class Main {}\"";

        JsonNode result = service.parseJsonWithRepair(truncated);

        assertNotNull(result);
        assertTrue(result.has("files"));
        assertEquals("src/Main.java", result.get("files").get(0).get("path").asText());
    }

    @Test
    void should_repair_truncated_json_in_string() {
        // JSON cut mid-string value (content field truncated)
        String truncated = "{\"files\": [{\"path\": \"src/Main.java\", \"content\": \"class Main { public void he";

        JsonNode result = service.parseJsonWithRepair(truncated);

        assertNotNull(result);
        assertTrue(result.has("files"));
    }

    @Test
    void should_repair_truncated_json_in_array() {
        // JSON cut mid-array: second file entry is incomplete
        String truncated = "{\"files\": [{\"path\": \"a.java\", \"content\": \"A\"}, {\"path\": \"b.java\"";

        JsonNode result = service.parseJsonWithRepair(truncated);

        assertNotNull(result);
        assertTrue(result.has("files"));
        // At least the first complete file should be accessible
        assertTrue(result.get("files").size() >= 1);
    }

    @Test
    void should_return_null_for_completely_invalid_json() {
        String invalid = "this is not json at all ??? <<<>>>";

        JsonNode result = service.parseJsonWithRepair(invalid);

        assertNull(result);
    }

    // --- findJsonErrorPosition tests ---

    @Test
    void should_find_error_position_in_malformed_json() {
        // Intentionally malformed: missing quotes around value
        String malformed = "{\"key\": value_without_quotes}";

        int errorPos = service.findJsonErrorPosition(malformed);

        assertTrue(errorPos > 0, "Error position should be positive for malformed JSON");
    }

    @Test
    void should_return_negative_one_for_valid_json() {
        String validJson = "{\"key\": \"value\"}";

        int errorPos = service.findJsonErrorPosition(validJson);

        assertEquals(-1, errorPos);
    }

    // --- closeJsonString tests ---

    @Test
    void should_close_open_json_constructs() {
        // Open object, open array, open object inside
        String openJson = "{\"files\": [{\"path\": \"a.java\"";

        String closed = service.closeJsonString(openJson);

        // Should be parseable after closing
        assertDoesNotThrow(() -> objectMapper.readTree(closed));
        JsonNode node = assertDoesNotThrow(() -> objectMapper.readTree(closed));
        assertTrue(node.has("files"));
    }

    @Test
    void should_close_open_string() {
        String openString = "{\"key\": \"value still going";

        String closed = service.closeJsonString(openString);

        assertDoesNotThrow(() -> objectMapper.readTree(closed));
    }

    @Test
    void should_trim_trailing_commas_before_closing() {
        String trailingComma = "{\"files\": [{\"path\": \"a.java\"},  ";

        String closed = service.closeJsonString(trailingComma);

        assertDoesNotThrow(() -> objectMapper.readTree(closed));
    }

    @Test
    void should_handle_escaped_quotes_in_strings() {
        String withEscapes = "{\"content\": \"line with \\\"quotes\\\" inside";

        String closed = service.closeJsonString(withEscapes);

        assertDoesNotThrow(() -> objectMapper.readTree(closed));
    }

    @Test
    void should_handle_nested_arrays_and_objects() {
        String nested = "{\"data\": [{\"items\": [{\"id\": 1";

        String closed = service.closeJsonString(nested);

        JsonNode node = assertDoesNotThrow(() -> objectMapper.readTree(closed));
        assertTrue(node.has("data"));
    }

    @Test
    void should_handle_already_complete_json() {
        String complete = "{\"key\": \"value\"}";

        String closed = service.closeJsonString(complete);

        assertEquals(complete, closed);
    }

    // --- Constants verification ---

    @Test
    void should_have_expected_constant_values() {
        assertEquals(100, JsonRepairService.MIN_ERROR_POSITION);
        assertEquals(50, JsonRepairService.MAX_BACKWARD_SEARCH_OFFSET);
        assertEquals(2000, JsonRepairService.MAX_TRUNCATION_SEARCH_WINDOW);
    }
}
