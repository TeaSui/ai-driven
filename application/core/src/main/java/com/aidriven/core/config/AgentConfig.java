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
        int recentMessagesToKeep,
        boolean guardrailsEnabled,
        int costBudgetPerTicket,
        boolean classifierUseLlm) {

    /** Backward-compatible constructor for existing code (Phase 1-2). */
    public AgentConfig(boolean enabled, String queueUrl, int maxTurns,
                       int maxWallClockSeconds, String triggerPrefix,
                       int tokenBudget, int recentMessagesToKeep) {
        this(enabled, queueUrl, maxTurns, maxWallClockSeconds, triggerPrefix,
                tokenBudget, recentMessagesToKeep, true, 200_000, false);
    }
}
