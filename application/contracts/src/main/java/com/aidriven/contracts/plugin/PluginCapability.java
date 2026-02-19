package com.aidriven.contracts.plugin;

/**
 * Types of capabilities a plugin can provide.
 */
public enum PluginCapability {
    /** Source control integration (e.g., GitLab, Azure DevOps) */
    SOURCE_CONTROL,

    /** Issue tracker integration (e.g., Linear, Notion, Shortcut) */
    ISSUE_TRACKER,

    /** AI model provider (e.g., OpenAI, Bedrock, local models) */
    AI_MODEL,

    /** Tool provider for agent mode (e.g., monitoring, messaging) */
    TOOL_PROVIDER
}
