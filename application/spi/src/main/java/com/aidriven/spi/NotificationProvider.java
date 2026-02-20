package com.aidriven.spi;

import java.util.Map;

/**
 * Service Provider Interface for notification/messaging integrations.
 * Implementations can wrap Slack, Microsoft Teams, Email (SES/SendGrid),
 * PagerDuty, etc.
 *
 * <p>Each tenant may use different notification channels.</p>
 */
public interface NotificationProvider {

    /**
     * Unique identifier for this provider (e.g., "slack", "teams", "email").
     */
    String providerId();

    /**
     * Human-readable display name.
     */
    String displayName();

    /**
     * Sends a notification message.
     *
     * @param channel   Channel/recipient identifier (e.g., Slack channel, email address)
     * @param subject   Message subject/title (may be ignored by some providers)
     * @param body      Message body (supports markdown for compatible providers)
     * @param metadata  Additional provider-specific metadata
     * @return Message/notification ID
     */
    String send(String channel, String subject, String body, Map<String, String> metadata) throws Exception;
}
