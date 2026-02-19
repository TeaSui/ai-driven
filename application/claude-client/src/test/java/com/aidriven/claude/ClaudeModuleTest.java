package com.aidriven.claude;

import com.aidriven.spi.ModuleCategory;
import com.aidriven.spi.ModuleContext;
import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.SimpleModuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeModuleTest {

    private ClaudeModule module;

    @BeforeEach
    void setUp() {
        module = new ClaudeModule();
    }

    @Test
    void should_have_correct_metadata() {
        assertEquals("claude", module.id());
        assertEquals("Claude AI (Anthropic)", module.displayName());
        assertEquals(ModuleCategory.AI_PROVIDER, module.category());
    }

    @Test
    void should_not_be_healthy_before_initialization() {
        assertFalse(module.isHealthy());
    }

    @Test
    void should_initialize_with_defaults() throws Exception {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .secret("apiKey", "sk-test-key")
                .build();

        module.initialize(ctx);

        assertTrue(module.isHealthy());
        assertNotNull(module.getClient());
        assertEquals("claude-opus-4-6", module.getModel());
    }

    @Test
    void should_initialize_with_custom_model() throws Exception {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .secret("apiKey", "sk-test-key")
                .config("model", "claude-sonnet-4-5")
                .config("maxTokens", "16384")
                .config("temperature", "0.5")
                .build();

        module.initialize(ctx);

        assertTrue(module.isHealthy());
        assertEquals("claude-sonnet-4-5", module.getModel());
    }

    @Test
    void should_throw_when_api_key_missing() {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1").build();

        assertThrows(ModuleInitializationException.class, () -> module.initialize(ctx));
    }

    @Test
    void should_throw_when_getting_client_before_init() {
        assertThrows(IllegalStateException.class, () -> module.getClient());
    }

    @Test
    void should_shutdown_cleanly() throws Exception {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .secret("apiKey", "sk-test-key")
                .build();

        module.initialize(ctx);
        assertTrue(module.isHealthy());

        module.shutdown();
        assertFalse(module.isHealthy());
    }
}
