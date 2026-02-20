package com.aidriven.tool.tracker;

import com.aidriven.spi.*;

/**
 * SPI module provider for the issue tracker tool provider.
 */
public class IssueTrackerModuleProvider implements ModuleProvider {

    private static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor
            .builder("tool-issue-tracker", ModuleCategory.ISSUE_TRACKER)
            .name("Issue Tracker Tool Provider")
            .version("1.0.0")
            .description("Exposes issue tracker operations as AI agent tools")
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
        return initialized ? HealthStatus.healthy("Issue tracker tools ready") : HealthStatus.notInitialized();
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }
}
