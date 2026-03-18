package com.aidriven.core.config;

/**
 * Immutable configuration for the AI Agent subsystem.
 *
 * <p>All fields are loaded from Spring Boot {@code AppProperties.AgentProperties}
 * and passed via constructor injection. This record is the canonical configuration
 * object consumed by {@code AgentOrchestrator} and related agent components.</p>
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
        boolean classifierUseLlm,

        /**
         * The @mention keyword the agent responds to (e.g. "ai" for @ai).
         * Configurable so teams using @claude or other AI tools in the same
         * project can avoid collisions. Controlled by AGENT_MENTION_KEYWORD.
         * Default: "ai"
         */
        String mentionKeyword,

        /**
         * Immutable Jira Cloud accountId of the bot service account.
         * Used for self-loop prevention. Controlled by AGENT_BOT_ACCOUNT_ID.
         * Nullable — falls back to display-name matching if not configured.
         */
        String botAccountId) {

    /** Convenience constructor preserving the 10-field API surface (used by tests). */
    public AgentConfig(boolean enabled, String queueUrl, int maxTurns,
            int maxWallClockSeconds, String triggerPrefix,
            int tokenBudget, int recentMessagesToKeep,
            boolean guardrailsEnabled, int costBudgetPerTicket,
            boolean classifierUseLlm) {
        this(enabled, queueUrl, maxTurns, maxWallClockSeconds, triggerPrefix,
                tokenBudget, recentMessagesToKeep,
                guardrailsEnabled, costBudgetPerTicket, classifierUseLlm,
                "ai", null);
    }

    /**
     * Returns the configured mention keyword (e.g. "ai").
     * Always lower-cased and trimmed; never null or blank.
     */
    public String effectiveMentionKeyword() {
        String kw = mentionKeyword != null ? mentionKeyword.strip().toLowerCase() : "ai";
        return kw.isBlank() ? "ai" : kw;
    }
}
