package com.aidriven.spi.model;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.jackson.Jacksonized;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Domain value object representing the context of an operation.
 * Encapsulates common operation metadata (correlation ID, user, timestamp,
 * etc.)
 * to reduce parameter bloat and improve traceability.
 */
@Getter
@Builder(toBuilder = true)
@Jacksonized
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class OperationContext {

    /**
     * The tenant ID.
     */
    @NonNull
    private final String tenantId;

    /**
     * Unique identifier for this operation (for tracing across logs).
     */
    @NonNull
    @Builder.Default
    private final String correlationId = UUID.randomUUID().toString();

    /**
     * The ticket being operated on.
     */
    private final TicketKey ticketKey;

    /**
     * User initiating the operation (e.g., from Jira webhook).
     */
    private final String userId;

    /**
     * User-provided request ID (idempotency key).
     */
    private final String requestId;

    /**
     * Timestamp when operation started.
     */
    @NonNull
    @Builder.Default
    private final Instant timestamp = Instant.now();

    /**
     * Optional source of operation (e.g., "webhook", "cli", "api").
     */
    private final String source;

    /**
     * Manual accessor for record-like compatibility.
     */
    public String tenantId() {
        return tenantId;
    }

    /**
     * Gets the project key from the ticket.
     */
    public String projectKey() {
        return ticketKey != null ? ticketKey.projectKey() : null;
    }

    /**
     * Gets the user ID if present.
     */
    @JsonIgnore
    public Optional<String> getUserId() {
        return Optional.ofNullable(userId);
    }

    /**
     * Gets the request ID if present (for idempotency).
     */
    @JsonIgnore
    public Optional<String> getRequestId() {
        return Optional.ofNullable(requestId);
    }

    /**
     * Gets the source if present.
     */
    @JsonIgnore
    public Optional<String> getSource() {
        return Optional.ofNullable(source);
    }

    @Override
    public String toString() {
        return String.format("OperationContext{tenantId='%s', correlationId='%s', ticket='%s'}",
                tenantId, correlationId, ticketKey);
    }
}
