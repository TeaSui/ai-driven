package com.teasui.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class NotificationEvent extends BaseEvent {

    public enum Type {
        EMAIL,
        SMS,
        WEBHOOK,
        IN_APP
    }

    public enum Priority {
        LOW, NORMAL, HIGH, CRITICAL
    }

    private Type type;
    private Priority priority;
    private String recipient;
    private String subject;
    private String body;
    private Map<String, String> templateVariables;
    private String templateId;

    public static NotificationEvent emailNotification(String tenantId, String recipient,
                                                       String subject, String body) {
        return NotificationEvent.builder()
                .eventId(generateEventId())
                .eventType("NOTIFICATION")
                .tenantId(tenantId)
                .occurredAt(Instant.now())
                .version(1)
                .type(Type.EMAIL)
                .priority(Priority.NORMAL)
                .recipient(recipient)
                .subject(subject)
                .body(body)
                .build();
    }
}
