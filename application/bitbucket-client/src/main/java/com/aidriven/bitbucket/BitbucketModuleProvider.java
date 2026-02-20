package com.aidriven.bitbucket;

import com.aidriven.spi.*;

/**
 * SPI module provider for the Bitbucket client.
 * Enables Bitbucket integration to be discovered and activated per-tenant.
 */
public class BitbucketModuleProvider implements ModuleProvider {

    private static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor
            .builder("bitbucket-client", ModuleCategory.SOURCE_CONTROL)
            .name("Bitbucket Cloud Client")
            .version("1.0.0")
            .description("Bitbucket Cloud REST API client for branches, commits, PRs, and code browsing")
            .requiredConfigs("bitbucket.secretArn")
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
            context.getConfig("bitbucket.secretArn").orElseThrow(() ->
                    new IllegalStateException("bitbucket.secretArn not configured"));
        } catch (Exception e) {
            throw new ModuleInitializationException("bitbucket-client", e.getMessage(), e);
        }
        this.initialized = true;
    }

    @Override
    public HealthStatus healthCheck() {
        return initialized ? HealthStatus.healthy("Bitbucket client ready") : HealthStatus.notInitialized();
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
