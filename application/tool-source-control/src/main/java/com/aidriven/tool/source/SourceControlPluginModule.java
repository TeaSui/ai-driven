package com.aidriven.tool.source;

import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.plugin.PluginDescriptor;
import com.aidriven.core.plugin.PluginModule;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.source.Platform;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plugin module for source control integrations (Bitbucket, GitHub).
 * Dynamically creates the appropriate client based on tenant configuration.
 */
@Slf4j
public class SourceControlPluginModule implements PluginModule {

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            "source_control",
            "Source Control Integration",
            "1.0.0",
            Set.of("source_control"),
            "Provides source control operations (branches, commits, PRs) via Bitbucket or GitHub");

    private final List<ToolProvider> toolProviders = new ArrayList<>();
    private final SourceControlClientFactory clientFactory;

    /** Default constructor for SPI discovery. */
    public SourceControlPluginModule() {
        this(new DefaultSourceControlClientFactory());
    }

    /** Constructor for testing with custom factory. */
    public SourceControlPluginModule(SourceControlClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(TenantContext tenantContext, SecretsService secretsService) {
        toolProviders.clear();

        String platformStr = tenantContext.getConfig("defaultPlatform", "BITBUCKET");
        Platform platform = Platform.fromString(platformStr);
        if (platform == null) platform = Platform.BITBUCKET;

        try {
            SourceControlClient client = clientFactory.create(platform, tenantContext, secretsService);
            toolProviders.add(new SourceControlToolProvider(client));
            log.info("Source control plugin initialized for tenant={} platform={}",
                    tenantContext.tenantId(), platform);
        } catch (Exception e) {
            log.error("Failed to initialize source control for tenant={}: {}",
                    tenantContext.tenantId(), e.getMessage(), e);
        }
    }

    @Override
    public List<ToolProvider> getToolProviders() {
        return List.copyOf(toolProviders);
    }

    /**
     * Factory interface for creating source control clients.
     * Allows testing without real API connections.
     */
    public interface SourceControlClientFactory {
        SourceControlClient create(Platform platform, TenantContext tenantContext, SecretsService secretsService);
    }

    /**
     * Default factory that creates real Bitbucket/GitHub clients from secrets.
     */
    private static class DefaultSourceControlClientFactory implements SourceControlClientFactory {
        @Override
        public SourceControlClient create(Platform platform, TenantContext tenantContext, SecretsService secretsService) {
            String secretArn = switch (platform) {
                case GITHUB -> tenantContext.getSecretArn("github")
                        .orElseThrow(() -> new IllegalStateException("GitHub secret ARN not configured for tenant " + tenantContext.tenantId()));
                case BITBUCKET -> tenantContext.getSecretArn("bitbucket")
                        .orElseThrow(() -> new IllegalStateException("Bitbucket secret ARN not configured for tenant " + tenantContext.tenantId()));
            };

            return switch (platform) {
                case GITHUB -> com.aidriven.github.GitHubClient.fromSecrets(secretsService, secretArn);
                case BITBUCKET -> com.aidriven.bitbucket.BitbucketClient.fromSecrets(secretsService, secretArn);
            };
        }
    }
}