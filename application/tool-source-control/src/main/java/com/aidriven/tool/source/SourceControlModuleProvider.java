package com.aidriven.tool.source;

import com.aidriven.spi.*;

/**
 * SPI module provider for the source control tool provider.
 */
public class SourceControlModuleProvider implements ModuleProvider {

    private static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor
            .builder("tool-source-control", ModuleCategory.SOURCE_CONTROL)
            .name("Source Control Tool Provider")
            .version("1.0.0")
            .description("Exposes source control operations as AI agent tools")
            .capabilities("agent-tools")
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
        return initialized ? HealthStatus.healthy("Source control tools ready") : HealthStatus.notInitialized();
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }
}
