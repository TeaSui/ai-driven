package com.aidriven.spi;

import com.aidriven.core.source.SourceControlClient;

/**
 * SPI interface for source control modules (GitHub, Bitbucket, GitLab, etc.).
 * Extends {@link ServiceModule} with typed access to the underlying client.
 */
public interface SourceControlModule extends ServiceModule {

    @Override
    default ModuleCategory category() {
        return ModuleCategory.SOURCE_CONTROL;
    }

    /**
     * Returns the initialized source control client.
     *
     * @throws IllegalStateException if the module is not initialized
     */
    SourceControlClient getClient();

    /**
     * Returns a client configured for a specific repository.
     *
     * @param owner Repository owner/workspace
     * @param repo  Repository name/slug
     * @return Client scoped to the specified repository
     */
    SourceControlClient getClient(String owner, String repo);
}
