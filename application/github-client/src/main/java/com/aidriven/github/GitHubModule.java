package com.aidriven.github;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.spi.ModuleContext;
import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.SourceControlModule;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * GitHub module implementing the {@link SourceControlModule} SPI.
 *
 * <p>Required secrets:</p>
 * <ul>
 *   <li>{@code token} — GitHub personal access token</li>
 *   <li>{@code owner} — Default repository owner</li>
 *   <li>{@code repo} — Default repository name</li>
 * </ul>
 */
@Slf4j
public class GitHubModule implements SourceControlModule {

    private GitHubClient client;
    private boolean initialized = false;

    @Override
    public String id() {
        return "github";
    }

    @Override
    public String displayName() {
        return "GitHub";
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of();
    }

    @Override
    public void initialize(ModuleContext context) throws ModuleInitializationException {
        try {
            String token = context.getSecret("token");
            String owner = context.getSecret("owner");
            String repo = context.getSecret("repo");

            String repoUrl = String.format("https://github.com/%s/%s", owner, repo);
            this.client = GitHubClient.fromRepoUrl(repoUrl, token);
            this.initialized = true;
            log.info("GitHubModule initialized for tenant={} repo={}/{}",
                    context.tenantId(), owner, repo);
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
    public SourceControlClient getClient() {
        if (!initialized) {
            throw new IllegalStateException("GitHubModule is not initialized");
        }
        return client;
    }

    @Override
    public SourceControlClient getClient(String owner, String repo) {
        if (!initialized) {
            throw new IllegalStateException("GitHubModule is not initialized");
        }
        return client.withRepository(owner, repo);
    }
}
