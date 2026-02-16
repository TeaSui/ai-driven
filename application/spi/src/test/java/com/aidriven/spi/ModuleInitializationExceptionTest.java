package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModuleInitializationExceptionTest {

    @Test
    void should_include_module_id_in_message() {
        ModuleInitializationException ex = new ModuleInitializationException("jira", "connection refused");

        assertEquals("jira", ex.getModuleId());
        assertTrue(ex.getMessage().contains("jira"));
        assertTrue(ex.getMessage().contains("connection refused"));
    }

    @Test
    void should_include_cause() {
        RuntimeException cause = new RuntimeException("timeout");
        ModuleInitializationException ex = new ModuleInitializationException("github", "failed", cause);

        assertEquals("github", ex.getModuleId());
        assertEquals(cause, ex.getCause());
    }
}
