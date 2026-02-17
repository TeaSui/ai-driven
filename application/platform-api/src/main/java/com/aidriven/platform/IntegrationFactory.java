package com.aidriven.platform;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.core.agent.AiClient;

/**
 * Factory interface for creating tenant-specific integration instances.
 *
 * <p>Implementations resolve the correct client based on the tenant's
 * configuration (which source control platform, which issue tracker, etc.).
 * This decouples the orchestration logic from specific vendor implementations.</p>
 *
 * <p>Each method returns a client configured for the given tenant context.
 * Implementations may cache clients per tenant for Lambda execution context reuse.</p>
 */
public interface IntegrationFactory {

    /**
     * Creates a source control client for the tenant.
     *
     * @param tenant The tenant context with platform and credential configuration
     * @return A configured SourceControlClient
     */
    SourceControlClient createSourceControlClient(TenantContext tenant);

    /**
     * Creates an issue tracker client for the tenant.
     *
     * @param tenant The tenant context with tracker configuration
     * @return A configured IssueTrackerClient
     */
    IssueTrackerClient createIssueTrackerClient(TenantContext tenant);

    /**
     * Creates an AI client for the tenant.
     *
     * @param tenant The tenant context with AI provider configuration
     * @return A configured AiClient
     */
    AiClient createAiClient(TenantContext tenant);
}
