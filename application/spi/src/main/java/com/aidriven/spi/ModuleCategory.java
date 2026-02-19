package com.aidriven.spi;

/**
 * Categories for service modules.
 * Used for discovery, validation, and tenant configuration.
 */
public enum ModuleCategory {

    /** Issue tracking: Jira, Linear, Shortcut, Notion */
    ISSUE_TRACKER,

    /** Source control: GitHub, Bitbucket, GitLab */
    SOURCE_CONTROL,

    /** AI model provider: Claude, OpenAI, Bedrock */
    AI_PROVIDER,

    /** Monitoring/observability: Datadog, New Relic, Grafana */
    MONITORING,

    /** Messaging: Slack, Teams, Discord */
    MESSAGING,

    /** Data/storage: databases, caches */
    DATA,

    /** Context: code context strategies */
    CONTEXT
}
