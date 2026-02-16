package com.aidriven.spi;

/**
 * SPI marker interface for issue tracker modules (Jira, Linear, Shortcut, etc.).
 *
 * <p>Modules implementing this interface provide issue tracking capabilities
 * and can be swapped per tenant. The platform uses this type to locate
 * the active issue tracker for a given tenant.</p>
 */
public interface IssueTrackerModule extends ServiceModule {

    /**
     * Module category constant for issue trackers.
     */
    String CATEGORY = "issue_tracker";
}
