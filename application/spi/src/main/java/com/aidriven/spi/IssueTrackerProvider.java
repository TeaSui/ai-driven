package com.aidriven.spi;

import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface for issue tracker integrations.
 * Implementations can wrap Jira, Linear, Notion, Shortcut, Azure Boards, etc.
 *
 * <p>Each tenant may use a different issue tracker.</p>
 */
public interface IssueTrackerProvider {

    /**
     * Unique identifier for this provider (e.g., "jira", "linear", "notion").
     */
    String providerId();

    /**
     * Human-readable display name.
     */
    String displayName();

    /**
     * Fetches a ticket/issue by its key.
     */
    TicketData getTicket(String ticketKey) throws Exception;

    /**
     * Adds a comment to a ticket.
     *
     * @return The ID of the created comment
     */
    String addComment(String ticketKey, String comment) throws Exception;

    /**
     * Edits an existing comment.
     */
    void editComment(String ticketKey, String commentId, String newBody) throws Exception;

    /**
     * Transitions a ticket to a new status.
     */
    void updateStatus(String ticketKey, String targetStatus) throws Exception;

    /**
     * Platform-agnostic ticket data.
     */
    record TicketData(
            String id,
            String key,
            String projectKey,
            String summary,
            String description,
            List<String> labels,
            String status,
            String priority,
            Map<String, Object> customFields) {}
}
