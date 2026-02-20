package com.aidriven.github;

import com.aidriven.spi.*;

/**
 * SPI module provider for the GitHub client.
 */
public class GitHubModuleProvider implements ModuleProvider {

    private static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor
            .builder("github-client", ModuleCategory.SOURCE_CONTROL)
            .name("GitHub Client")
            .version("1.0.0")
            .description("GitHub REST API v3 client for branches, commits, PRs, and code browsing")
            .requiredConfigs("github.secretArn")
            .capabilities("source-control", "code-browsing", "pr-management")
            .build();

    private volatile boolean initialized = false;

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(TenantContext context) throws ModuleInitializationException {
        try {
            context.getConfig("github.secretArn").orElseThrow(() ->
                    new IllegalStateException("github.secretArn not configured"));
        } catch (Exception e) {
            throw new ModuleInitializationException("github-client", e.getMessage(), e);
        }
        this.initialized = true;
    }

    @Override
    public HealthStatus healthCheck() {
        return initialized ? HealthStatus.healthy("GitHub client ready") : HealthStatus.notInitialized();
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
