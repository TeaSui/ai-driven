package com.aidriven.spi.provider;

import com.aidriven.spi.model.OperationContext;
import java.util.List;
import java.util.Map;

/**
 * SPI for Issue Tracking systems (Jira, Linear, GitHub Issues).
 */
public interface IssueTrackerProvider {
    /** Returns the unique identifier for this provider (e.g., "jira"). */
    String getName();

    /** Fetches ticket details. */
    Map<String, Object> getTicketDetails(OperationContext context, String ticketKey);

    /** Adds a comment to a ticket. */
    void postComment(OperationContext context, String ticketKey, String body);

    /** Updates ticket labels. */
    void updateLabels(OperationContext context, String ticketKey, List<String> labelsToAdd,
            List<String> labelsToRemove);

    /** Updates ticket status. */
    void updateStatus(OperationContext context, String ticketKey, String statusName);
}
