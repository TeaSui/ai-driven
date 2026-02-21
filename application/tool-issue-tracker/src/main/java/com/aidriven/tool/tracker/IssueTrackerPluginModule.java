package com.aidriven.tool.tracker;

import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.plugin.PluginDescriptor;
import com.aidriven.core.plugin.PluginModule;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.tenant.TenantContext;
import com.aidriven.core.tracker.IssueTrackerClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plugin module for issue tracker integrations (Jira, and future: Linear, Notion, etc.).
 */
@Slf4j
public class IssueTrackerPluginModule implements PluginModule {

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            "jira",
            "Issue Tracker Integration",
            "1.0.0",
            Set.of("issue_tracker"),
            "Provides issue tracking operations (tickets, comments, transitions) via Jira");

    private final List<ToolProvider> toolProviders = new ArrayList<>();
    private final IssueTrackerClientFactory clientFactory;

    /** Default constructor for SPI discovery. */
    public IssueTrackerPluginModule() {
        this(new DefaultIssueTrackerClientFactory());
    }

    /** Constructor for testing. */
    public IssueTrackerPluginModule(IssueTrackerClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(TenantContext tenantContext, SecretsService secretsService) {
        toolProviders.clear();

        try {
            IssueTrackerClient client = clientFactory.create(tenantContext, secretsService);
            toolProviders.add(new IssueTrackerToolProvider(client));
            log.info("Issue tracker plugin initialized for tenant={}", tenantContext.tenantId());
        } catch (Exception e) {
            log.error("Failed to initialize issue tracker for tenant={}: {}",
                    tenantContext.tenantId(), e.getMessage(), e);
        }
    }

    @Override
    public List<ToolProvider> getToolProviders() {
        return List.copyOf(toolProviders);
    }

    public interface IssueTrackerClientFactory {
        IssueTrackerClient create(TenantContext tenantContext, SecretsService secretsService);
    }

    private static class DefaultIssueTrackerClientFactory implements IssueTrackerClientFactory {
        @Override
        public IssueTrackerClient create(TenantContext tenantContext, SecretsService secretsService) {
            String secretArn = tenantContext.getSecretArn("jira")
                    .orElseThrow(() -> new IllegalStateException(
                            "Jira secret ARN not configured for tenant " + tenantContext.tenantId()));
            return com.aidriven.jira.JiraClient.fromSecrets(secretsService, secretArn);
        }
    }
}