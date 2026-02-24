package com.aidriven.core.service;

import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Service for managing idempotency of event processing.
 * Uses DynamoDB with TTL to prevent duplicate processing.
 */
@Slf4j
public class IdempotencyService {

    private static final int DEFAULT_TTL_HOURS = 24;
    private static final int DEFAULT_MAX_EVENTS_PER_HOUR = 100;

    private final TicketStateRepository repository;
    private final int ttlHours;
    private final int maxEventsPerHour;

    public IdempotencyService(TicketStateRepository repository) {
        this(repository, DEFAULT_TTL_HOURS, DEFAULT_MAX_EVENTS_PER_HOUR);
    }

    public IdempotencyService(TicketStateRepository repository, int ttlHours) {
        this(repository, ttlHours, DEFAULT_MAX_EVENTS_PER_HOUR);
    }

    public IdempotencyService(TicketStateRepository repository, int ttlHours, int maxEventsPerHour) {
        this.repository = repository;
        this.ttlHours = ttlHours;
        this.maxEventsPerHour = maxEventsPerHour;
    }

    /**
     * Checks if an event is a duplicate.
     * 
     * @param tenantId The tenant ID
     * @param ticketId The ticket ID
     * @param eventId  The unique event ID (e.g. comment ID)
     * @return true if duplicate, false otherwise
     */
    public boolean isDuplicate(String tenantId, String ticketId, String ticketKey, String eventId) {
        String pk = TicketState.createPk(tenantId, ticketId);
        String sk = TicketState.createIdempotencySk(eventId);

        Optional<TicketState> existing = repository.get(pk, sk);

        if (existing.isPresent()) {
            log.info("Duplicate event detected: tenantId={}, ticketId={}, eventId={}", tenantId, ticketId, eventId);
            return true;
        }

        return false;
    }

    /**
     * Atomically checks if an event has been processed and records it as processed
     * if not.
     *
     * <p>
     * This is the primary method for idempotency guard. It uses DynamoDB
     * conditional writes
     * to prevent race conditions in concurrent environments.
     *
     * @param ticketId The ticket ID (used as partition key prefix)
     * @param eventId  The unique event/webhook-delivery ID
     * @return {@code true} if this is a <em>new</em> event and was successfully
     *         recorded;
     *         {@code false} if the event was already processed (duplicate)
     */
    public boolean checkAndRecord(String tenantId, String ticketId, String ticketKey, String eventId) {
        // Defensive null checks for non-null required fields in TicketState
        String safeTenantId = tenantId != null ? tenantId : "default";
        String safeTicketId = ticketId != null ? ticketId : "UNKNOWN";
        String safeTicketKey = ticketKey != null ? ticketKey : "UNKNOWN";
        String safeEventId = eventId != null ? eventId : "UNKNOWN";

        Instant now = Instant.now();
        long ttlEpochSeconds = now.plus(ttlHours, ChronoUnit.HOURS).getEpochSecond();

        TicketState idempotencyRecord = TicketState.builder()
                .pk(TicketState.createPk(safeTenantId, safeTicketId))
                .sk(TicketState.createIdempotencySk(safeEventId))
                .ticketId(safeTicketId)
                .ticketKey(safeTicketKey)
                .ttl(ttlEpochSeconds)
                .createdAt(now)
                .build();

        return repository.saveIfNotExists(idempotencyRecord);
    }

    /**
     * Checks if the tenant has exceeded their hourly rate limit.
     * Throws an exception if the limit is exceeded.
     */
    public void checkRateLimit(String tenantId) {
        String currentHour = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH")
                .withZone(java.time.ZoneId.of("UTC"))
                .format(Instant.now());

        // Set TTL to 48 hours for rate limit records to allow for debugging
        long ttlEpochSeconds = Instant.now().plus(48, ChronoUnit.HOURS).getEpochSecond();

        long count = repository.incrementTenantEventCount(tenantId, currentHour, ttlEpochSeconds);

        if (count > maxEventsPerHour) {
            log.warn("Rate limit exceeded for tenant {}: {} > {}", tenantId, count, maxEventsPerHour);
            throw new RuntimeException(String.format(
                    "Rate limit of %d requests per hour exceeded for your tenant. Please try again later.",
                    maxEventsPerHour));
        }
    }
}
