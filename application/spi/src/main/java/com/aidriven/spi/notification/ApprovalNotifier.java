package com.aidriven.spi.notification;

/**
 * Interface for notifying external systems (like Slack) when an action
 * requires human approval.
 */
public interface ApprovalNotifier {

    /**
     * Sent when a high-risk action is intercepted and pending approval.
     */
    void notifyPending(PendingApprovalContext context);

    /**
     * Context passed to the notifier containing details about the pending approval.
     */
    record PendingApprovalContext(
            String ticketKey,
            String toolName,
            String actionDescription,
            String generatedByModel,
            String triggerReason,
            long timeoutSeconds) {
    }
}
