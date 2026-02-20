package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModuleInitializationExceptionTest {

    @Test
    void should_include_module_id_in_message() {
        ModuleInitializationException ex = new ModuleInitializationException("github", "Connection refused");
        assertTrue(ex.getMessage().contains("github"));
        assertTrue(ex.getMessage().contains("Connection refused"));
        assertEquals("github", ex.getModuleId());
    }

    @Test
    void should_include_cause() {
        RuntimeException cause = new RuntimeException("timeout");
        ModuleInitializationException ex = new ModuleInitializationException("jira", "Init failed", cause);
        assertEquals(cause, ex.getCause());
        assertEquals("jira", ex.getModuleId());
    }
}
