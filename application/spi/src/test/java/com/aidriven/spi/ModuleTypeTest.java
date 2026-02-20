package com.aidriven.spi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ModuleTypeTest {

    @ParameterizedTest
    @CsvSource({
            "source-control, SOURCE_CONTROL",
            "issue-tracker, ISSUE_TRACKER",
            "ai-provider, AI_PROVIDER",
            "messaging, MESSAGING",
            "monitoring, MONITORING",
            "secrets, SECRETS",
            "storage, STORAGE",
            "extension, EXTENSION"
    })
    void should_resolve_from_value(String value, ModuleType expected) {
        assertEquals(expected, ModuleType.fromValue(value));
    }

    @Test
    void should_throw_for_unknown_value() {
        assertThrows(IllegalArgumentException.class, () -> ModuleType.fromValue("unknown"));
    }

    @Test
    void should_be_case_insensitive() {
        assertEquals(ModuleType.SOURCE_CONTROL, ModuleType.fromValue("SOURCE-CONTROL"));
        assertEquals(ModuleType.AI_PROVIDER, ModuleType.fromValue("AI-Provider"));
    }

    @Test
    void should_return_correct_value() {
        assertEquals("source-control", ModuleType.SOURCE_CONTROL.getValue());
        assertEquals("issue-tracker", ModuleType.ISSUE_TRACKER.getValue());
    }
}
