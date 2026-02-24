package com.aidriven.core.tracker;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.spi.model.OperationContext;
import java.util.List;

/**
 * Platform-agnostic interface for issue tracker operations.
 * Updated for multi-tenancy via OperationContext.
 */
public interface IssueTrackerClient {

    TicketInfo getTicket(OperationContext context, String ticketKey) throws Exception;

    String addComment(OperationContext context, String ticketKey, String comment) throws Exception;

    default void editComment(OperationContext context, String ticketKey, String commentId, String newBody)
            throws Exception {
        throw new UnsupportedOperationException("editComment not supported by this implementation");
    }

    void transitionTicket(OperationContext context, String ticketKey, String transitionId) throws Exception;

    void updateStatus(OperationContext context, String ticketKey, String targetStatusName) throws Exception;

    List<Transition> getTransitions(OperationContext context, String ticketKey) throws Exception;

    record Transition(String id, String toStatus) {
    }
}
