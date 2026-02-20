package com.aidriven.jira;

import com.aidriven.spi.*;

/**
 * SPI module provider for the Jira client.
 * Enables Jira integration to be discovered and activated per-tenant.
 *
 * <p>Registration: {@code META-INF/services/com.aidriven.spi.ModuleProvider}</p>
 */
public class JiraModuleProvider implements ModuleProvider {

    private static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor
            .builder("jira-client", ModuleCategory.ISSUE_TRACKER)
            .name("Jira Cloud Client")
            .version("1.0.0")
            .description("Jira Cloud REST API client for issue tracking, comments, and transitions")
            .requiredConfigs("jira.secretArn")
            .capabilities("issue-tracking", "comment-management", "status-transitions")
            .build();

    private volatile boolean initialized = false;
    private TenantContext tenantContext;

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(TenantContext context) throws ModuleInitializationException {
        this.tenantContext = context;
        // Validate required config is present
        try {
            context.getConfig("jira.secretArn").orElseThrow(() ->
                    new IllegalStateException("jira.secretArn not configured"));
        } catch (Exception e) {
            throw new ModuleInitializationException("jira-client", e.getMessage(), e);
        }
        this.initialized = true;
    }

    @Override
    public HealthStatus healthCheck() {
        if (!initialized) {
            return HealthStatus.notInitialized();
        }
        return HealthStatus.healthy("Jira client ready");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void shutdown() {
        this.initialized = false;
        this.tenantContext = null;
    }
}
