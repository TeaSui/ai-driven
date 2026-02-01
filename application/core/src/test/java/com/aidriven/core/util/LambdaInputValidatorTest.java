package com.aidriven.core.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LambdaInputValidatorTest {

    @Test
    void should_return_value_when_field_present() {
        Map<String, Object> input = Map.of("name", "test-value");

        String result = LambdaInputValidator.requireString(input, "name");

        assertEquals("test-value", result);
    }

    @Test
    void should_throw_when_field_missing() {
        Map<String, Object> input = Map.of("other", "value");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LambdaInputValidator.requireString(input, "name"));

        assertTrue(ex.getMessage().contains("Missing required field: name"));
    }

    @Test
    void should_throw_when_field_is_null() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LambdaInputValidator.requireString(input, "name"));

        assertTrue(ex.getMessage().contains("Missing required field: name"));
    }

    @Test
    void should_throw_when_field_is_blank() {
        Map<String, Object> input = Map.of("name", "   ");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LambdaInputValidator.requireString(input, "name"));

        assertTrue(ex.getMessage().contains("Required field is blank: name"));
    }

    @Test
    void should_convert_non_string_value_to_string() {
        Map<String, Object> input = Map.of("count", 42);

        String result = LambdaInputValidator.requireString(input, "count");

        assertEquals("42", result);
    }

    @Test
    void should_return_default_when_optional_field_missing() {
        Map<String, Object> input = Map.of("other", "value");

        String result = LambdaInputValidator.optionalString(input, "name", "default");

        assertEquals("default", result);
    }

    @Test
    void should_return_value_when_optional_field_present() {
        Map<String, Object> input = Map.of("name", "actual");

        String result = LambdaInputValidator.optionalString(input, "name", "default");

        assertEquals("actual", result);
    }

    @Test
    void should_return_default_when_optional_field_is_blank() {
        Map<String, Object> input = Map.of("name", "  ");

        String result = LambdaInputValidator.optionalString(input, "name", "default");

        assertEquals("default", result);
    }

    @Test
    void should_return_default_when_optional_field_is_null() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", null);

        String result = LambdaInputValidator.optionalString(input, "name", "default");

        assertEquals("default", result);
    }

    @Test
    void should_throw_for_null_input_in_requireNonEmpty() {
        assertThrows(NullPointerException.class,
                () -> LambdaInputValidator.requireNonEmptyInput(null, "TestHandler"));
    }

    @Test
    void should_throw_for_empty_input_in_requireNonEmpty() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LambdaInputValidator.requireNonEmptyInput(Map.of(), "TestHandler"));

        assertTrue(ex.getMessage().contains("TestHandler"));
        assertTrue(ex.getMessage().contains("must not be empty"));
    }

    @Test
    void should_pass_for_non_empty_input() {
        assertDoesNotThrow(
                () -> LambdaInputValidator.requireNonEmptyInput(Map.of("key", "val"), "TestHandler"));
    }

    @Test
    void should_throw_for_unset_env_variable() {
        assertThrows(IllegalStateException.class,
                () -> LambdaInputValidator.requireEnv("NONEXISTENT_ENV_VAR_FOR_TEST_12345"));
    }
}
