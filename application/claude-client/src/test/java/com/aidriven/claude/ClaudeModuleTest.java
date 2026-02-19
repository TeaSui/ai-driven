package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.spi.ServiceProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeModuleTest {

    @Test
    void should_have_correct_metadata() {
        ClaudeModule module = new ClaudeModule();

        assertEquals("claude", module.name());
        assertEquals("1.0.0", module.version());
        assertEquals(List.of(AiClient.class), module.providedServices());
        assertEquals(40, module.priority());
    }

    @Test
    void should_require_api_key() {
        ClaudeModule module = new ClaudeModule();

        assertTrue(module.requiredConfigKeys().contains("CLAUDE_API_KEY"));
    }

    @Test
    void should_have_optional_defaults() {
        ClaudeModule module = new ClaudeModule();

        Map<String, String> defaults = module.optionalConfigDefaults();
        assertEquals("claude-opus-4-6", defaults.get("CLAUDE_MODEL"));
        assertEquals("32768", defaults.get("CLAUDE_MAX_TOKENS"));
        assertEquals("0.2", defaults.get("CLAUDE_TEMPERATURE"));
    }

    @Test
    void should_register_as_default_ai_client() {
        ClaudeModule module = new ClaudeModule();
        ServiceProviderRegistry registry = new ServiceProviderRegistry();

        module.initialize(registry, Map.of(
                "CLAUDE_API_KEY", "sk-test-key",
                "CLAUDE_MODEL", "claude-sonnet-4-5",
                "CLAUDE_MAX_TOKENS", "16384",
                "CLAUDE_TEMPERATURE", "0.5"));

        assertTrue(registry.isRegistered(AiClient.class, "claude"));
        assertNotNull(registry.getDefault(AiClient.class));
    }

    @Test
    void should_use_defaults_when_optional_config_missing() {
        ClaudeModule module = new ClaudeModule();
        ServiceProviderRegistry registry = new ServiceProviderRegistry();

        // Only provide required key, let defaults handle the rest
        Map<String, String> config = new java.util.HashMap<>(module.optionalConfigDefaults());
        config.put("CLAUDE_API_KEY", "sk-test-key");

        module.initialize(registry, config);

        assertTrue(registry.isRegistered(AiClient.class, "claude"));
    }
}
