package com.aidriven.claude;

import com.aidriven.spi.AiEngineModule;
import com.aidriven.spi.ModuleInitializationException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Claude AI module implementing the {@link AiEngineModule} SPI.
 *
 * <p>Required configuration keys:
 * <ul>
 *   <li>{@code apiKey} — Claude API key</li>
 * </ul>
 * Optional:
 * <ul>
 *   <li>{@code model} — Model ID (default: claude-opus-4-6)</li>
 *   <li>{@code maxTokens} — Max output tokens (default: 32768)</li>
 *   <li>{@code temperature} — Temperature (default: 0.2)</li>
 * </ul>
 * </p>
 */
@Slf4j
public class ClaudeModule implements AiEngineModule {

    private ClaudeClient client;
    private volatile boolean healthy = false;

    @Override
    public String moduleId() {
        return "claude";
    }

    @Override
    public String displayName() {
        return "Claude AI";
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public void initialize(Map<String, String> config) throws ModuleInitializationException {
        try {
            String apiKey = requireConfig(config, "apiKey");
            String model = config.getOrDefault("model", "claude-opus-4-6");
            int maxTokens = Integer.parseInt(config.getOrDefault("maxTokens", "32768"));
            double temperature = Double.parseDouble(config.getOrDefault("temperature", "0.2"));

            this.client = new ClaudeClient(apiKey, model, maxTokens, temperature);
            this.healthy = true;

            log.info("Claude module initialized: model={}, maxTokens={}", model, maxTokens);
        } catch (ModuleInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModuleInitializationException("claude", e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public void shutdown() {
        this.healthy = false;
        this.client = null;
        log.info("Claude module shut down");
    }

    public ClaudeClient getClient() {
        if (client == null) {
            throw new IllegalStateException("Claude module is not initialized");
        }
        return client;
    }

    private String requireConfig(Map<String, String> config, String key) throws ModuleInitializationException {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new ModuleInitializationException("claude", "Missing required config: " + key);
        }
        return value;
    }
}
