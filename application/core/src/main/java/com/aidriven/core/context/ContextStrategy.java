package com.aidriven.core.context;

import com.aidriven.core.model.TicketInfo;

/**
 * Strategy interface for building code context.
 * Implementations define different ways to gather relevant code files for a
 * ticket.
 */
public interface ContextStrategy {

    /**
     * Builds a context string for the given ticket.
     * 
     * @param ticket Information about the Jira ticket (key, summary, description)
     * @param branch The git branch to fetch context from
     * @return A markdown-formatted string containing the code context, or null if
     *         the strategy fails/skips.
     */
    String buildContext(TicketInfo ticket, String branch);
}
