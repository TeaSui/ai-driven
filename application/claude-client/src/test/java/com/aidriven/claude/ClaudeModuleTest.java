package com.aidriven.claude;

import com.aidriven.spi.AiEngineModule;
import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.ServiceModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeModuleTest {

    private ClaudeModule module;

    @BeforeEach
    void setUp() {
        module = new ClaudeModule();
    }

    @Test
    void should_have_correct_module_id() {
        assertEquals("claude", module.moduleId());
    }

    @Test
    void should_implement_ai_engine_module() {
        assertTrue(module instanceof AiEngineModule);
    }

    @Test
    void should_initialize_with_api_key() throws Exception {
        module.initialize(Map.of("apiKey", "sk-test-key"));

        assertTrue(module.isHealthy());
        assertNotNull(module.getClient());
    }

    @Test
    void should_initialize_with_custom_model() throws Exception {
        module.initialize(Map.of(
                "apiKey", "sk-test",
                "model", "claude-sonnet-4-5",
                "maxTokens", "16384",
                "temperature", "0.5"));

        assertTrue(module.isHealthy());
        assertEquals("claude-sonnet-4-5", module.getClient().getModel());
    }

    @Test
    void should_fail_without_api_key() {
        assertThrows(ModuleInitializationException.class,
                () -> module.initialize(Map.of("model", "claude-opus-4-6")));
    }

    @Test
    void should_throw_when_getting_client_before_init() {
        assertThrows(IllegalStateException.class, module::getClient);
    }

    @Test
    void should_shutdown_cleanly() throws Exception {
        module.initialize(Map.of("apiKey", "sk-test"));

        module.shutdown();

        assertFalse(module.isHealthy());
    }

    @Test
    void should_be_discoverable_via_service_loader() {
        ServiceLoader<ServiceModule> loader = ServiceLoader.load(ServiceModule.class);
        boolean found = false;
        for (ServiceModule m : loader) {
            if ("claude".equals(m.moduleId())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "ClaudeModule should be discoverable via ServiceLoader");
    }
}
