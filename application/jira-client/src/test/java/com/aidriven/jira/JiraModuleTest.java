package com.aidriven.jira;

import com.aidriven.spi.ModuleCategory;
import com.aidriven.spi.ModuleContext;
import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.SimpleModuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JiraModuleTest {

    private JiraModule module;

    @BeforeEach
    void setUp() {
        module = new JiraModule();
    }

    @Test
    void should_have_correct_metadata() {
        assertEquals("jira", module.id());
        assertEquals("Jira Cloud", module.displayName());
        assertEquals(ModuleCategory.ISSUE_TRACKER, module.category());
        assertTrue(module.requiredConfigKeys().contains("baseUrl"));
    }

    @Test
    void should_not_be_healthy_before_initialization() {
        assertFalse(module.isHealthy());
    }

    @Test
    void should_initialize_successfully() throws Exception {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .config("baseUrl", "https://test.atlassian.net")
                .secret("email", "test@test.com")
                .secret("apiToken", "test-token")
                .build();

        module.initialize(ctx);

        assertTrue(module.isHealthy());
        assertNotNull(module.getClient());
    }

    @Test
    void should_throw_when_baseUrl_missing() {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .secret("email", "test@test.com")
                .secret("apiToken", "test-token")
                .build();

        assertThrows(ModuleInitializationException.class, () -> module.initialize(ctx));
    }

    @Test
    void should_throw_when_secret_missing() {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .config("baseUrl", "https://test.atlassian.net")
                .build();

        assertThrows(ModuleInitializationException.class, () -> module.initialize(ctx));
    }

    @Test
    void should_throw_when_getting_client_before_init() {
        assertThrows(IllegalStateException.class, () -> module.getClient());
    }

    @Test
    void should_shutdown_cleanly() throws Exception {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .config("baseUrl", "https://test.atlassian.net")
                .secret("email", "test@test.com")
                .secret("apiToken", "test-token")
                .build();

        module.initialize(ctx);
        assertTrue(module.isHealthy());

        module.shutdown();
        assertFalse(module.isHealthy());
    }
}
