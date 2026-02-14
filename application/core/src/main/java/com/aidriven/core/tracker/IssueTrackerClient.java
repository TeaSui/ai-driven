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
     */
    void addComment(String ticketKey, String comment) throws Exception;

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
