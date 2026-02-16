package com.aidriven.spi;

/**
 * SPI marker interface for source control modules (GitHub, Bitbucket, GitLab, etc.).
 *
 * <p>Modules implementing this interface provide source control capabilities
 * and can be swapped per tenant.</p>
 */
public interface SourceControlModule extends ServiceModule {

    /**
     * Module category constant for source control.
     */
    String CATEGORY = "source_control";
}
