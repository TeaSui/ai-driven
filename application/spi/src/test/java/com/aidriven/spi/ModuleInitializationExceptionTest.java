package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModuleInitializationExceptionTest {

    @Test
    void should_create_with_message() {
        ModuleInitializationException ex = new ModuleInitializationException("jira", "Connection refused");

        assertEquals("jira", ex.getModuleId());
        assertTrue(ex.getMessage().contains("jira"));
        assertTrue(ex.getMessage().contains("Connection refused"));
    }

    @Test
    void should_create_with_cause() {
        RuntimeException cause = new RuntimeException("timeout");
        ModuleInitializationException ex = new ModuleInitializationException("github", "Failed", cause);

        assertEquals("github", ex.getModuleId());
        assertEquals(cause, ex.getCause());
    }
}
