package com.aidriven.core.tracker;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.spi.model.OperationContext;
import java.util.List;

/**
 * Platform-agnostic interface for issue/ticket tracker operations.
 *
 * <p>This interface abstracts the common operations needed to interact with
 * issue tracking systems during the AI agent workflow: fetching ticket details,
 * adding comments (for progress updates), and transitioning ticket status
 * (e.g., moving from "To Do" to "In Progress" to "Done").</p>
 *
 * <h3>Known Implementations</h3>
 * <ul>
 *   <li>{@code JiraClient} -- Atlassian Jira Cloud REST API implementation</li>
 * </ul>
 *
 * <h3>Multi-Tenancy</h3>
 * <p>All operations accept an {@link OperationContext} that carries tenant,
 * project, and tracing information. Implementations resolve credentials and
 * base URLs from this context.</p>
 *
 * <h3>Workflow Integration</h3>
 * <p>The typical agent workflow uses this client to:
 * <ol>
 *   <li>Fetch ticket details via {@link #getTicket} to understand requirements</li>
 *   <li>Post progress comments via {@link #addComment} during execution</li>
 *   <li>Transition the ticket status via {@link #updateStatus} upon completion</li>
 * </ol>
 *
 * @see com.aidriven.core.model.TicketInfo
 * @since 1.0
 */
public interface IssueTrackerClient {

    /**
     * Fetches the full details of a ticket/issue from the tracker.
     *
     * <p>The returned {@link TicketInfo} contains the ticket summary,
     * description, status, labels, assignee, and other metadata needed by
     * the agent to understand and work on the ticket.</p>
     *
     * @param context   the operation context for tenant and project resolution
     * @param ticketKey the unique ticket identifier (e.g., {@code "PROJ-123"}
     *                  for Jira)
     * @return the ticket details; never {@code null}
     * @throws Exception if the ticket cannot be fetched (not found, permission
     *                   denied, network errors)
     */
    TicketInfo getTicket(OperationContext context, String ticketKey) throws Exception;

    /**
     * Adds a comment to a ticket.
     *
     * <p>Comments are used by the agent to post progress updates, analysis
     * results, and final summaries. The comment body typically supports the
     * platform's markup format (e.g., Atlassian Document Format for Jira).</p>
     *
     * @param context   the operation context
     * @param ticketKey the unique ticket identifier
     * @param comment   the comment body text
     * @return the ID of the created comment
     * @throws Exception if the comment cannot be added (ticket not found,
     *                   permission denied)
     */
    String addComment(OperationContext context, String ticketKey, String comment) throws Exception;

    /**
     * Edits an existing comment on a ticket.
     *
     * <p>This is an optional operation. The default implementation throws
     * {@link UnsupportedOperationException}. Implementations that support
     * comment editing should override this method.</p>
     *
     * @param context   the operation context
     * @param ticketKey the unique ticket identifier
     * @param commentId the ID of the comment to edit (as returned by
     *                  {@link #addComment})
     * @param newBody   the new comment body text
     * @throws UnsupportedOperationException if the implementation does not
     *                                       support comment editing
     * @throws Exception                     if the edit fails
     */
    default void editComment(OperationContext context, String ticketKey, String commentId, String newBody)
            throws Exception {
        throw new UnsupportedOperationException("editComment not supported by this implementation");
    }

    /**
     * Transitions a ticket to a new status using a specific transition ID.
     *
     * <p>This is a low-level operation that requires knowing the exact
     * transition ID. For a higher-level approach that resolves the transition
     * by target status name, use {@link #updateStatus} instead.</p>
     *
     * @param context      the operation context
     * @param ticketKey    the unique ticket identifier
     * @param transitionId the platform-specific transition ID (e.g., Jira
     *                     transition ID)
     * @throws Exception if the transition fails (invalid transition, permission
     *                   denied, workflow constraints)
     * @see #updateStatus(OperationContext, String, String)
     * @see #getTransitions(OperationContext, String)
     */
    void transitionTicket(OperationContext context, String ticketKey, String transitionId) throws Exception;

    /**
     * Updates a ticket's status by resolving and executing the appropriate
     * workflow transition.
     *
     * <p>Unlike {@link #transitionTicket}, this method accepts a human-readable
     * target status name (e.g., {@code "In Progress"}, {@code "Done"}) and
     * resolves the correct transition ID internally by querying available
     * transitions.</p>
     *
     * @param context          the operation context
     * @param ticketKey        the unique ticket identifier
     * @param targetStatusName the desired target status name (case handling is
     *                         implementation-specific)
     * @throws Exception if no valid transition to the target status exists, or
     *                   if the transition fails
     */
    void updateStatus(OperationContext context, String ticketKey, String targetStatusName) throws Exception;

    /**
     * Retrieves the list of available workflow transitions for a ticket.
     *
     * <p>The available transitions depend on the ticket's current status and
     * the project's workflow configuration. This is useful for discovering
     * which status changes are valid before attempting a transition.</p>
     *
     * @param context   the operation context
     * @param ticketKey the unique ticket identifier
     * @return a list of available transitions, each containing the transition
     *         ID and target status name; never {@code null}
     * @throws Exception if the transitions cannot be retrieved
     */
    List<Transition> getTransitions(OperationContext context, String ticketKey) throws Exception;

    /**
     * Represents a single available workflow transition for a ticket.
     *
     * @param id       the platform-specific transition identifier used by
     *                 {@link #transitionTicket}
     * @param toStatus the human-readable name of the target status (e.g.,
     *                 {@code "In Progress"}, {@code "Done"})
     */
    record Transition(String id, String toStatus) {
    }
}
