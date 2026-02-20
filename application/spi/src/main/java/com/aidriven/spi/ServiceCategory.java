package com.aidriven.spi;

/**
 * Categories of pluggable service modules.
 * Each category defines a contract that modules must implement.
 */
public enum ServiceCategory {

    /** Source control platforms (Bitbucket, GitHub, GitLab, etc.) */
    SOURCE_CONTROL,

    /** Issue/project trackers (Jira, Linear, Shortcut, etc.) */
    ISSUE_TRACKER,

    /** AI/LLM providers (Claude, OpenAI, Bedrock, etc.) */
    AI_PROVIDER,

    /** Code context strategies (full repo, smart/incremental, etc.) */
    CONTEXT_STRATEGY,

    /** Notification/messaging integrations (Slack, Teams, email, etc.) */
    MESSAGING,

    /** Monitoring/observability integrations (Datadog, CloudWatch, etc.) */
    MONITORING,

    /** Custom tool providers via MCP or direct integration */
    TOOL_PROVIDER
}
