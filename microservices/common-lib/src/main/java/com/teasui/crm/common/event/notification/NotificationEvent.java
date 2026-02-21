package com.teasui.crm.common.event.notification;

import com.teasui.crm.common.event.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/**
 * Event published to trigger notifications across channels.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class NotificationEvent extends BaseEvent {

    public static final String EVENT_TYPE = "NOTIFICATION";

    private String recipientUserId;
    private List<String> recipientEmails;
    private NotificationType type;
    private NotificationChannel channel;
    private String subject;
    private String templateId;
    private Map<String, Object> templateVariables;
    private String body;
    private Priority priority;

    public enum NotificationType {
        WORKFLOW_COMPLETED,
        WORKFLOW_FAILED,
        INTEGRATION_ERROR,
        SYSTEM_ALERT,
        USER_INVITATION,
        CUSTOM
    }

    public enum NotificationChannel {
        EMAIL,
        IN_APP,
        WEBHOOK,
        ALL
    }

    public enum Priority {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
