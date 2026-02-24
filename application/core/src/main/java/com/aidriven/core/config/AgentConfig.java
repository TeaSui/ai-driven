package com.aidriven.core.config;

/**
 * Immutable configuration for the AI Agent subsystem.
 *
 * <p>All fields are loaded once from environment variables at cold-start
 * (see {@link AppConfig#getAgentConfig()}) and never mutated.
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

    /** Full legacy constructor (Phase 1-2 API). */
    public AgentConfig(boolean enabled, String queueUrl, int maxTurns,
            int maxWallClockSeconds, String triggerPrefix,
            int tokenBudget, int recentMessagesToKeep) {
        this(enabled, queueUrl, maxTurns, maxWallClockSeconds, triggerPrefix,
                tokenBudget, recentMessagesToKeep,
                true, 200_000, false, "ai", null);
    }

    /** Convenience constructor preserving the 10-field API surface. */
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
