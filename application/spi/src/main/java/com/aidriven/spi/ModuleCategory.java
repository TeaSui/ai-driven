package com.aidriven.spi;

/**
 * Categories of pluggable modules.
 * Used for grouping, validation (e.g., at least one ISSUE_TRACKER required),
 * and UI display in a future admin dashboard.
 */
public enum ModuleCategory {

    /** Issue/project tracking (Jira, Linear, Shortcut, etc.) */
    ISSUE_TRACKER,

    /** Source control platforms (Bitbucket, GitHub, GitLab, etc.) */
    SOURCE_CONTROL,

    /** AI model providers (Claude, GPT, Gemini, etc.) */
    AI_ENGINE,

    /** Code context strategies (full-repo, smart, incremental) */
    CODE_CONTEXT,

    /** Observability & monitoring (Datadog, New Relic, etc.) */
    MONITORING,

    /** Messaging & notifications (Slack, Teams, Discord, etc.) */
    MESSAGING,

    /** Data & storage integrations (databases, caches, etc.) */
    DATA,

    /** Infrastructure & deployment (AWS, GCP, Terraform, etc.) */
    INFRASTRUCTURE,

    /** Orchestration layer (Step Functions, Lambda, etc.) */
    ORCHESTRATION,

    /** MCP bridge for external tool servers */
    MCP_BRIDGE
}
