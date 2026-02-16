package com.aidriven.core.config;

/**
 * Configuration for the AI Agent behavior.
 */
public record AgentConfig(
        boolean enabled,
        String queueUrl,
        int maxTurns,
        int maxWallClockSeconds,
        String triggerPrefix,
        int tokenBudget,
        int recentMessagesToKeep) {
}
