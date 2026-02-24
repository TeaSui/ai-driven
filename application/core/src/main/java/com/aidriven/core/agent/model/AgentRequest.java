package com.aidriven.core.agent.model;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.spi.model.OperationContext;

/**
 * Request payload for the agent orchestrator.
 *
 * @param ticketKey     The Jira ticket key (e.g., "PROJ-123")
 * @param platform      The platform (e.g., "JIRA", "GITHUB")
 * @param commentBody   Raw comment text from Jira (with @ai prefix stripped)
 * @param commentAuthor Display name of the comment author
 * @param ticketInfo    Full ticket metadata (labels, description, etc.)
 * @param ackCommentId  ID of the acknowledgment comment to update with progress
 * @param context       Security context for multi-tenancy
 * @param prContext     Additional context for Pull Requests (diff hunk, file
 *                      path)
 */
public record AgentRequest(
                String ticketKey,
                String platform,
                String commentBody,
                String commentAuthor,
                TicketInfo ticketInfo,
                String ackCommentId,
                OperationContext context,
                java.util.Map<String, String> prContext) {
}
