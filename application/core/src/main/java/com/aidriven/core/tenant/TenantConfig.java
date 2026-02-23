package com.aidriven.core.tenant;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tenant-specific configuration for workflow automation.
 * Each tenant (company) has its own configuration that drives
 * which plugins are enabled, which platforms are used, and
 * what business rules apply.
 */
@Data
@Builder(toBuilder = true)
public class TenantConfig {

    /** Unique tenant identifier (e.g., "acme-corp", "startup-xyz") */
    private String tenantId;

    /** Human-readable tenant name */
    private String tenantName;

    /** Source control platform preference */
    private String defaultPlatform;

    /** Default workspace/owner for source control */
    private String defaultWorkspace;

    /** Default repository slug */
    private String defaultRepo;

    /** Issue tracker type (e.g., "JIRA", "LINEAR", "GITHUB_ISSUES") */
    private String issueTrackerType;

    /** AWS Secrets Manager ARN for Jira credentials */
    private String jiraSecretArn;

    /** AWS Secrets Manager ARN for Bitbucket credentials */
    private String bitbucketSecretArn;

    /** AWS Secrets Manager ARN for GitHub credentials */
    private String githubSecretArn;

    /** AWS Secrets Manager ARN for Claude API key */
    private String claudeSecretArn;

    /** DynamoDB table name for this tenant's state */
    private String dynamoDbTableName;

    /** S3 bucket for code context storage */
    private String codeContextBucket;

    /** Step Functions state machine ARN */
    private String stateMachineArn;

    /** SQS queue URL for agent tasks */
    private String agentQueueUrl;

    /** Enabled plugin namespaces (e.g., "monitoring", "messaging") */
    private Set<String> enabledPlugins;

    /** Tenant-specific label prefix for triggering automation (default: "ai-generate") */
    private String triggerLabel;

    /** Agent trigger prefix (default: "@ai") */
    private String agentTriggerPrefix;

    /** Maximum tokens per agent conversation */
    private int agentTokenBudget;

    /** Maximum turns per agent session */
    private int agentMaxTurns;

    /** Whether guardrails are enabled for this tenant */
    private boolean guardrailsEnabled;

    /** Custom metadata for tenant-specific extensions */
    private Map<String, String> metadata;

    /** MCP server configurations as JSON string */
    private String mcpServersConfig;

    /** Whether this tenant is active */
    private boolean active;

    /**
     * Returns the effective trigger label, defaulting to "ai-generate".
     */
    public String effectiveTriggerLabel() {
        return triggerLabel != null && !triggerLabel.isBlank() ? triggerLabel : "ai-generate";
    }

    /**
     * Returns the effective agent trigger prefix, defaulting to "@ai".
     */
    public String effectiveAgentTriggerPrefix() {
        return agentTriggerPrefix != null && !agentTriggerPrefix.isBlank() ? agentTriggerPrefix : "@ai";
    }

    /**
     * Returns the effective agent token budget, defaulting to 50000.
     */
    public int effectiveAgentTokenBudget() {
        return agentTokenBudget > 0 ? agentTokenBudget : 50_000;
    }

    /**
     * Returns the effective agent max turns, defaulting to 10.
     */
    public int effectiveAgentMaxTurns() {
        return agentMaxTurns > 0 ? agentMaxTurns : 10;
    }

    /**
     * Checks if a plugin is enabled for this tenant.
     */
    public boolean isPluginEnabled(String namespace) {
        if (enabledPlugins == null || enabledPlugins.isEmpty()) {
            return false;
        }
        return enabledPlugins.contains(namespace);
    }
}
