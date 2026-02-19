package com.aidriven.contracts.tracker;

/**
 * Platform-agnostic contract for issue tracker operations.
 * <p>
 * This interface lives in the contracts module so that custom integrations
 * (Linear, Notion, Shortcut, Azure Boards, etc.) can implement it
 * without depending on core.
 * </p>
 *
 * <p>Existing implementations: JiraClient.</p>
 */
public interface IssueTrackerOperations {

    /**
     * Fetches a ticket/issue by its key (e.g., "PROJ-123").
     */
    TicketData getTicket(String ticketKey) throws Exception;

    /**
     * Adds a comment to a ticket.
     *
     * @return The ID of the created comment (or null if not applicable).
     */
    String addComment(String ticketKey, String comment) throws Exception;

    /**
     * Edits an existing comment on a ticket.
     */
    default void editComment(String ticketKey, String commentId, String newBody) throws Exception {
        throw new UnsupportedOperationException("editComment not supported by this implementation");
    }

    /**
     * Transitions a ticket to a new status using a transition ID.
     */
    void transitionTicket(String ticketKey, String transitionId) throws Exception;

    /**
     * Updates the status of a ticket by name.
     */
    void updateStatus(String ticketKey, String targetStatusName) throws Exception;

    /**
     * Portable ticket data returned by getTicket.
     */
    record TicketData(
            String ticketId,
            String ticketKey,
            String projectKey,
            String summary,
            String description,
            java.util.List<String> labels,
            String status,
            String assignee,
            String reporter,
            String priority) {
    }
}
