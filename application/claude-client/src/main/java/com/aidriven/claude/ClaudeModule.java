package com.aidriven.claude;

import com.aidriven.core.agent.AiClient;
import com.aidriven.spi.AiProviderModule;
import com.aidriven.spi.ModuleContext;
import com.aidriven.spi.ModuleInitializationException;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Claude AI module implementing the {@link AiProviderModule} SPI.
 *
 * <p>Required secrets:</p>
 * <ul>
 *   <li>{@code apiKey} — Claude API key</li>
 * </ul>
 *
 * <p>Optional configuration:</p>
 * <ul>
 *   <li>{@code model} — Model identifier (default: claude-opus-4-6)</li>
 *   <li>{@code maxTokens} — Max output tokens (default: 32768)</li>
 *   <li>{@code temperature} — Temperature (default: 0.2)</li>
 * </ul>
 */
@Slf4j
public class ClaudeModule implements AiProviderModule {

    private ClaudeClient client;
    private String model;
    private boolean initialized = false;

    @Override
    public String id() {
        return "claude";
    }

    @Override
    public String displayName() {
        return "Claude AI (Anthropic)";
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of();
    }

    @Override
    public void initialize(ModuleContext context) throws ModuleInitializationException {
        try {
            String apiKey = context.getSecret("apiKey");
            this.model = context.getConfig("model", "claude-opus-4-6");
            int maxTokens = context.getIntConfig("maxTokens", 32768);
            double temperature = Double.parseDouble(context.getConfig("temperature", "0.2"));

            this.client = new ClaudeClient(apiKey, model, maxTokens, temperature);
            this.initialized = true;
            log.info("ClaudeModule initialized for tenant={} model={} maxTokens={}",
                    context.tenantId(), model, maxTokens);
        } catch (Exception e) {
            throw new ModuleInitializationException(id(), e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        return initialized && client != null;
    }

    @Override
    public void shutdown() {
        this.client = null;
        this.initialized = false;
    }

    @Override
    public AiClient getClient() {
        if (!initialized) {
            throw new IllegalStateException("ClaudeModule is not initialized");
        }
        return client;
    }

    @Override
    public String getModel() {
        return model;
    }
}
