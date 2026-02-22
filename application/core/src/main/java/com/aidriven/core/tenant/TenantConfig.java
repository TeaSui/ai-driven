package com.aidriven.core.tenant;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tenant-specific configuration for workflow automation.
 * Each tenant (company) can have its own set of tools, integrations, and workflow settings.
 */
@Data
@Builder(toBuilder = true)
public class TenantConfig {

    /** Unique tenant identifier (e.g., company slug). */
    private final String tenantId;

    /** Human-readable tenant name. */
    private final String tenantName;

    /** Jira secret ARN for this tenant. */
    private final String jiraSecretArn;

    /** Bitbucket secret ARN for this tenant (nullable). */
    private final String bitbucketSecretArn;

    /** GitHub secret ARN for this tenant (nullable). */
    private final String gitHubSecretArn;

    /** Claude secret ARN for this tenant (nullable — falls back to global). */
    private final String claudeSecretArn;

    /** Default source control platform for this tenant. */
    @Builder.Default
    private final String defaultPlatform = "BITBUCKET";

    /** Default workspace/owner for this tenant's repositories. */
    private final String defaultWorkspace;

    /** Default repository slug for this tenant. */
    private final String defaultRepo;

    /** Enabled tool namespaces for this tenant (beyond core tools). */
    @Builder.Default
    private final Set<String> enabledTools = Set.of();

    /** Tenant-specific MCP server configurations (JSON array string). */
    @Builder.Default
    private final String mcpServersConfig = "[]";

    /** Maximum agent turns for this tenant. */
    @Builder.Default
    private final int maxAgentTurns = 10;

    /** Token budget per ticket for this tenant. */
    @Builder.Default
    private final int tokenBudgetPerTicket = 200_000;

    /** Whether guardrails are enabled for this tenant. */
    @Builder.Default
    private final boolean guardrailsEnabled = true;

    /** Custom workflow labels that trigger the pipeline for this tenant. */
    @Builder.Default
    private final List<String> triggerLabels = List.of("ai-generate", "ai-test", "dry-run", "test-mode");

    /** Tenant-specific branch prefix. */
    @Builder.Default
    private final String branchPrefix = "ai/";

    /** Additional metadata for tenant-specific customization. */
    @Builder.Default
    private final Map<String, String> metadata = Map.of();

    /**
     * Returns true if this tenant has GitHub configured.
     */
    public boolean hasGitHub() {
        return gitHubSecretArn != null && !gitHubSecretArn.isBlank();
    }

    /**
     * Returns true if this tenant has Bitbucket configured.
     */
    public boolean hasBitbucket() {
        return bitbucketSecretArn != null && !bitbucketSecretArn.isBlank();
    }

    /**
     * Returns true if a specific tool namespace is enabled for this tenant.
     */
    public boolean isToolEnabled(String namespace) {
        return enabledTools.contains(namespace);
    }
}
