package com.aidriven.spi;

import com.aidriven.core.tracker.IssueTrackerClient;

/**
 * SPI interface for issue tracker modules (Jira, Linear, Shortcut, etc.).
 * Extends {@link ServiceModule} with typed access to the underlying client.
 */
public interface IssueTrackerModule extends ServiceModule {

    @Override
    default ModuleCategory category() {
        return ModuleCategory.ISSUE_TRACKER;
    }

    /**
     * Returns the initialized issue tracker client.
     *
     * @throws IllegalStateException if the module is not initialized
     */
    IssueTrackerClient getClient();
}
