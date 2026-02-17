package com.aidriven.platform;

import java.util.List;
import java.util.Map;

/**
 * Describes a pluggable module that can be composed into a tenant's
 * workflow automation stack.
 *
 * <p>Each module declares:
 * <ul>
 *   <li>A unique identifier and human-readable name</li>
 *   <li>The type of integration it provides</li>
 *   <li>Required configuration keys (validated at tenant onboarding)</li>
 *   <li>Optional feature flags</li>
 * </ul>
 *
 * <p>Modules are discovered via {@link ModuleRegistry} and composed
 * per-tenant based on their subscription and configuration.</p>
 */
public record ModuleDescriptor(
        String moduleId,
        String displayName,
        ModuleType type,
        String description,
        List<String> requiredConfigKeys,
        Map<String, String> defaultConfig) {

    /**
     * Categories of modules that can be composed.
     */
    public enum ModuleType {
        /** Source control integration (Bitbucket, GitHub, GitLab, etc.) */
        SOURCE_CONTROL,
        /** Issue tracker integration (Jira, Linear, Notion, etc.) */
        ISSUE_TRACKER,
        /** AI model provider (Claude, GPT, Bedrock, etc.) */
        AI_PROVIDER,
        /** Code context strategy (full repo, smart, incremental) */
        CONTEXT_STRATEGY,
        /** External tool via MCP or direct integration */
        TOOL_PROVIDER,
        /** Notification / messaging integration */
        NOTIFICATION,
        /** Observability / monitoring integration */
        OBSERVABILITY
    }
}
