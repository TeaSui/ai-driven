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
    
    private final TicketStateRepository repository;
    private final int ttlHours;
    
    public IdempotencyService(TicketStateRepository repository) {
        this(repository, DEFAULT_TTL_HOURS);
    }
    
    public IdempotencyService(TicketStateRepository repository, int ttlHours) {
        this.repository = repository;
        this.ttlHours = ttlHours;
    }
    
    /**
     * Checks if an event has already been processed.
     * 
     * @param ticketId The ticket ID
     * @param eventId The unique event ID (e.g., webhook delivery ID)
     * @return true if already processed (duplicate), false if new
     */
    public boolean isDuplicate(String ticketId, String eventId) {
        String pk = TicketState.createPk(ticketId);
        String sk = TicketState.createIdempotencySk(eventId);
        
        Optional<TicketState> existing = repository.get(pk, sk);
        
        if (existing.isPresent()) {
            log.info("Duplicate event detected: ticketId={}, eventId={}", ticketId, eventId);
            return true;
        }
        
        return false;
    }
    
    /**
     * Records that an event has been processed.
     * Note: This is now less atomic than checkAndRecord.
     * 
     * @param ticketId The ticket ID
     * @param eventId The unique event ID
     */
    public void recordProcessed(String ticketId, String eventId) {
        checkAndRecord(ticketId, eventId);
    }
    
    /**
     * Atomically checks and records an event if not already processed.
     * Uses DynamoDB conditional write for atomicity.
     * 
     * @param ticketId The ticket ID
     * @param eventId The unique event ID
     * @return true if this is a new event and was recorded, false if duplicate
     */
    public boolean checkAndRecord(String ticketId, String eventId) {
        Instant now = Instant.now();
        long ttlEpochSeconds = now.plus(ttlHours, ChronoUnit.HOURS).getEpochSecond();
        
        TicketState idempotencyRecord = TicketState.builder()
                .pk(TicketState.createPk(ticketId))
                .sk(TicketState.createIdempotencySk(eventId))
                .ticketId(ticketId)
                .ttl(ttlEpochSeconds)
                .createdAt(now)
                .build();
        
        return repository.saveIfNotExists(idempotencyRecord);
    }
}
