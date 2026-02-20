package com.aidriven.tool.context;

import com.aidriven.spi.*;

/**
 * SPI module provider for the code context tool provider.
 */
public class CodeContextModuleProvider implements ModuleProvider {

    private static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor
            .builder("tool-code-context", ModuleCategory.CODE_CONTEXT)
            .name("Code Context Tool Provider")
            .version("1.0.0")
            .description("Provides code context building strategies for AI agents")
            .capabilities("agent-tools", "code-context")
            .build();

    private volatile boolean initialized = false;

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(TenantContext context) {
        this.initialized = true;
    }

    @Override
    public HealthStatus healthCheck() {
        return initialized ? HealthStatus.healthy("Code context tools ready") : HealthStatus.notInitialized();
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }
}
