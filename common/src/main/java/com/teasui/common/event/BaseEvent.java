package com.teasui.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TenantEvent.class, name = "TENANT"),
        @JsonSubTypes.Type(value = WorkflowEvent.class, name = "WORKFLOW"),
        @JsonSubTypes.Type(value = NotificationEvent.class, name = "NOTIFICATION")
})
public abstract class BaseEvent {

    private String eventId;
    private String eventType;
    private String tenantId;
    private Instant occurredAt;
    private String correlationId;
    private int version;

    public static String generateEventId() {
        return UUID.randomUUID().toString();
    }
}
