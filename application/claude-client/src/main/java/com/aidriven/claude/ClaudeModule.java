package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.core.spi.ModuleDescriptor;
import com.aidriven.core.spi.ServiceProviderRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Module descriptor for the Claude AI integration.
 *
 * <p>Registers a {@link ClaudeClient} as an {@link AiClient}
 * provider with qualifier "claude".</p>
 *
 * <p>Required config keys:</p>
 * <ul>
 *   <li>{@code CLAUDE_API_KEY} — Claude API key</li>
 * </ul>
 *
 * <p>Optional config keys:</p>
 * <ul>
 *   <li>{@code CLAUDE_MODEL} — Model ID (default: claude-opus-4-6)</li>
 *   <li>{@code CLAUDE_MAX_TOKENS} — Max output tokens (default: 32768)</li>
 *   <li>{@code CLAUDE_TEMPERATURE} — Temperature (default: 0.2)</li>
 * </ul>
 */
@Slf4j
public class ClaudeModule implements ModuleDescriptor {

    @Override
    public String name() {
        return "claude";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public List<Class<?>> providedServices() {
        return List.of(AiClient.class);
    }

    @Override
    public List<String> requiredConfigKeys() {
        return List.of("CLAUDE_API_KEY");
    }

    @Override
    public Map<String, String> optionalConfigDefaults() {
        return Map.of(
                "CLAUDE_MODEL", "claude-opus-4-6",
                "CLAUDE_MAX_TOKENS", "32768",
                "CLAUDE_TEMPERATURE", "0.2");
    }

    @Override
    public void initialize(ServiceProviderRegistry registry, Map<String, String> config) {
        String apiKey = config.get("CLAUDE_API_KEY");
        String model = config.getOrDefault("CLAUDE_MODEL", "claude-opus-4-6");
        int maxTokens = Integer.parseInt(config.getOrDefault("CLAUDE_MAX_TOKENS", "32768"));
        double temperature = Double.parseDouble(config.getOrDefault("CLAUDE_TEMPERATURE", "0.2"));

        ClaudeClient client = new ClaudeClient(apiKey, model, maxTokens, temperature);

        registry.registerDefault(AiClient.class, "claude", client);
        log.info("Claude module initialized with model={}", model);
    }

    @Override
    public int priority() {
        return 40;
    }
}
