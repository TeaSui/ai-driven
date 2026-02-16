package com.aidriven.core.tracker;

import com.aidriven.core.model.TicketInfo;

/**
 * Platform-agnostic interface for issue tracker operations.
 * Implementations include Jira, and in the future Linear, Notion, Shortcut,
 * etc.
 */
public interface IssueTrackerClient {

    /**
     * Fetches a ticket/issue by its key (e.g., "PROJ-123").
     *
     * @param ticketKey The ticket key
     * @return Full ticket information
     */
    TicketInfo getTicket(String ticketKey) throws Exception;

    /**
     * Adds a comment to a ticket.
     *
     * @param ticketKey The ticket key
     * @param comment   The comment text
     * @return The ID of the created comment (or null if not applicable/available).
     */
    String addComment(String ticketKey, String comment) throws Exception;

    /**
     * Edits an existing comment on a ticket (replaces content in-place).
     *
     * @param ticketKey The ticket key
     * @param commentId The ID of the comment to edit
     * @param newBody   The new comment text
     */
    default void editComment(String ticketKey, String commentId, String newBody) throws Exception {
        // Default no-op for implementations that don't support editing.
        // Subclasses should override (e.g., JiraClient).
        throw new UnsupportedOperationException("editComment not supported by this implementation");
    }

    /**
     * Transitions a ticket to a new status using a transition ID.
     *
     * @param ticketKey    The ticket key
     * @param transitionId The ID of the transition to execute
     */
    void transitionTicket(String ticketKey, String transitionId) throws Exception;

    /**
     * Updates the status of a ticket by name (convenience method).
     * Implementations should look up the transition ID automatically.
     *
     * @param ticketKey        The ticket key
     * @param targetStatusName The name of the target status (e.g., "In Review",
     *                         "Done")
     */
    void updateStatus(String ticketKey, String targetStatusName) throws Exception;
}
