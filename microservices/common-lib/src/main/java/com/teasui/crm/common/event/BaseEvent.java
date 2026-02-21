package com.teasui.crm.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Base class for all domain events exchanged between microservices.
 * All events are published to RabbitMQ and consumed by interested services.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEvent {

    private String eventId;
    private String eventType;
    private String tenantId;
    private String correlationId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime occurredAt;

    private String sourceService;

    public static String generateEventId() {
        return UUID.randomUUID().toString();
    }

    public void initDefaults(String sourceService) {
        if (this.eventId == null) {
            this.eventId = generateEventId();
        }
        if (this.occurredAt == null) {
            this.occurredAt = LocalDateTime.now();
        }
        this.sourceService = sourceService;
    }
}
