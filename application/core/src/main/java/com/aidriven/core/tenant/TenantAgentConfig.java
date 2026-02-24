package com.aidriven.core.tenant;

import lombok.Builder;
import lombok.Data;

/**
 * Tenant-specific agent configuration overrides.
 * Allows each tenant to customize agent behavior independently.
 */
@Data
@Builder(toBuilder = true)
public class TenantAgentConfig {

    /** Maximum number of ReAct loop turns. */
    @Builder.Default
    private int maxTurns = 10;

    /** Token budget per conversation. */
    @Builder.Default
    private int tokenBudget = 50_000;

    /** Cost budget per ticket (tokens). */
    @Builder.Default
    private int costBudgetPerTicket = 200_000;

    /** Number of recent messages to keep in context window. */
    @Builder.Default
    private int recentMessagesToKeep = 2;

    /** Whether guardrails are enabled for this tenant. */
    @Builder.Default
    private boolean guardrailsEnabled = true;

    /** Whether to use LLM for intent classification. */
    @Builder.Default
    private boolean classifierUseLlm = false;

    /** Trigger prefix for agent commands. */
    @Builder.Default
    private String triggerPrefix = "@ai";
}
