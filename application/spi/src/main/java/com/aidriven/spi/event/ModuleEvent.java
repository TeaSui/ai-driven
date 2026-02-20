package com.aidriven.spi.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an event emitted by a module for inter-module communication.
 * Enables loose coupling between modules — a source control module can emit
 * a "pr.merged" event without knowing which modules consume it.
 *
 * <p>Events are lightweight and serializable for potential cross-service
 * communication via SQS/SNS in a distributed deployment.</p>
 */
public record ModuleEvent(
        String type,
        String sourceModule,
        String tenantId,
        Map<String, Object> payload,
        Instant timestamp) {

    public ModuleEvent {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(sourceModule, "sourceModule must not be null");
        if (payload == null) payload = Map.of();
        if (timestamp == null) timestamp = Instant.now();
    }

    public static ModuleEvent of(String type, String sourceModule, String tenantId, Map<String, Object> payload) {
        return new ModuleEvent(type, sourceModule, tenantId, payload, Instant.now());
    }
}
