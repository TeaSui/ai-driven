package com.aidriven.claude;

import com.aidriven.spi.*;

/**
 * SPI module provider for the Claude AI client.
 */
public class ClaudeModuleProvider implements ModuleProvider {

    private static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor
            .builder("claude-client", ModuleCategory.AI_ENGINE)
            .name("Claude AI Client")
            .version("1.0.0")
            .description("Anthropic Claude API client with auto-continuation and tool-use support")
            .requiredConfigs("claude.secretArn")
            .capabilities("ai-generation", "tool-use", "auto-continuation")
            .build();

    private volatile boolean initialized = false;

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(TenantContext context) throws ModuleInitializationException {
        try {
            context.getConfig("claude.secretArn").orElseThrow(() ->
                    new IllegalStateException("claude.secretArn not configured"));
        } catch (Exception e) {
            throw new ModuleInitializationException("claude-client", e.getMessage(), e);
        }
        this.initialized = true;
    }

    @Override
    public HealthStatus healthCheck() {
        return initialized ? HealthStatus.healthy("Claude client ready") : HealthStatus.notInitialized();
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void shutdown() {
        this.initialized = false;
    }
}
