package com.aidriven.core.agent.model;

import com.aidriven.core.model.TicketInfo;

/**
 * Request payload for the agent orchestrator.
 *
 * @param ticketKey     The Jira ticket key (e.g., "PROJ-123")
 * @param commentBody   Raw comment text from Jira (with @ai prefix stripped)
 * @param commentAuthor Display name of the comment author
 * @param ticketInfo    Full ticket metadata (labels, description, etc.)
 * @param ackCommentId  ID of the acknowledgment comment to update with progress
 */
public record AgentRequest(
                String ticketKey,
                String commentBody,
                String commentAuthor,
                TicketInfo ticketInfo,
                String ackCommentId) {
}
