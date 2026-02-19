package com.aidriven.bitbucket;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.spi.ModuleContext;
import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.SourceControlModule;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Bitbucket Cloud module implementing the {@link SourceControlModule} SPI.
 *
 * <p>Required secrets:</p>
 * <ul>
 *   <li>{@code username} — Bitbucket username</li>
 *   <li>{@code appPassword} — Bitbucket app password</li>
 *   <li>{@code workspace} — Default workspace</li>
 *   <li>{@code repoSlug} — Default repository slug</li>
 * </ul>
 */
@Slf4j
public class BitbucketModule implements SourceControlModule {

    private BitbucketClient client;
    private boolean initialized = false;

    @Override
    public String id() {
        return "bitbucket";
    }

    @Override
    public String displayName() {
        return "Bitbucket Cloud";
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of();
    }

    @Override
    public void initialize(ModuleContext context) throws ModuleInitializationException {
        try {
            String workspace = context.getSecret("workspace");
            String repoSlug = context.getSecret("repoSlug");
            String username = context.getSecret("username");
            String appPassword = context.getSecret("appPassword");

            this.client = BitbucketClient.fromRepoUrl(
                    String.format("https://bitbucket.org/%s/%s", workspace, repoSlug),
                    username, appPassword);
            this.initialized = true;
            log.info("BitbucketModule initialized for tenant={} workspace={}/{}",
                    context.tenantId(), workspace, repoSlug);
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
            throw new IllegalStateException("BitbucketModule is not initialized");
        }
        return client;
    }

    @Override
    public SourceControlClient getClient(String owner, String repo) {
        if (!initialized) {
            throw new IllegalStateException("BitbucketModule is not initialized");
        }
        return client.withRepository(owner, repo);
    }
}
