package com.aidriven.core.tenant;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tenant-specific configuration for workflow automation.
 * Each tenant (company) can have its own set of enabled plugins,
 * source control platform, issue tracker, and custom settings.
 */
@Data
@Builder(toBuilder = true)
public class TenantConfig {

    /** Unique tenant identifier (e.g., "acme-corp", "startup-xyz"). */
    private String tenantId;

    /** Human-readable tenant name. */
    private String tenantName;

    /** Source control platform: BITBUCKET or GITHUB. */
    private String platform;

    /** Secret ARN for source control credentials. */
    private String sourceControlSecretArn;

    /** Secret ARN for issue tracker credentials. */
    private String issueTrackerSecretArn;

    /** Secret ARN for AI model credentials. */
    private String aiSecretArn;

    /** Default repository owner/workspace. */
    private String defaultRepoOwner;

    /** Default repository slug. */
    private String defaultRepo;

    /** Enabled plugin namespaces (e.g., ["monitoring", "messaging"]). */
    @Builder.Default
    private Set<String> enabledPlugins = Set.of();

    /** Jira label that triggers the AI pipeline for this tenant. */
    @Builder.Default
    private String triggerLabel = "ai-generate";

    /** Agent mode trigger prefix. */
    @Builder.Default
    private String agentTriggerPrefix = "@ai";

    /** Maximum turns for agent mode. */
    @Builder.Default
    private int agentMaxTurns = 10;

    /** Token budget per ticket for cost control. */
    @Builder.Default
    private int tokenBudgetPerTicket = 200_000;

    /** Whether guardrails are enabled for this tenant. */
    @Builder.Default
    private boolean guardrailsEnabled = true;

    /** Whether agent mode is enabled for this tenant. */
    @Builder.Default
    private boolean agentEnabled = false;

    /** Custom workflow steps (plugin-specific configuration). */
    @Builder.Default
    private Map<String, Object> pluginConfig = Map.of();

    /** Branch prefix for AI-generated branches. */
    @Builder.Default
    private String branchPrefix = "ai/";

    /** Claude model override for this tenant (null = use default). */
    private String claudeModel;

    /** Whether this tenant config is active. */
    @Builder.Default
    private boolean active = true;
}
