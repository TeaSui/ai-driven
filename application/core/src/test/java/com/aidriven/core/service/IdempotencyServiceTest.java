package com.aidriven.core.service;

import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class IdempotencyServiceTest {

    @Mock
    private TicketStateRepository repository;

    private IdempotencyService service;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new IdempotencyService(repository);
    }

    @Test
    public void testIsDuplicate_Found() {
        when(repository.get(anyString(), anyString())).thenReturn(Optional.of(TicketState.builder().build()));
        
        assertTrue(service.isDuplicate("TICKET-1", "EVENT-1"));
        verify(repository).get(eq("TICKET#TICKET-1"), eq("IDEMPOTENCY#EVENT-1"));
    }

    @Test
    public void testIsDuplicate_NotFound() {
        when(repository.get(anyString(), anyString())).thenReturn(Optional.empty());
        
        assertFalse(service.isDuplicate("TICKET-1", "EVENT-1"));
    }

    @Test
    public void testRecordProcessed() {
        when(repository.saveIfNotExists(any(TicketState.class))).thenReturn(true);
        service.recordProcessed("TICKET-1", "EVENT-1");
        verify(repository).saveIfNotExists(any(TicketState.class));
    }

    @Test
    public void testCheckAndRecord_New() {
        when(repository.saveIfNotExists(any(TicketState.class))).thenReturn(true);
        
        assertTrue(service.checkAndRecord("TICKET-1", "EVENT-1"));
        verify(repository).saveIfNotExists(any(TicketState.class));
    }

    @Test
    public void testCheckAndRecord_Duplicate() {
        when(repository.saveIfNotExists(any(TicketState.class))).thenReturn(false);
        
        assertFalse(service.checkAndRecord("TICKET-1", "EVENT-1"));
        verify(repository).saveIfNotExists(any(TicketState.class));
    }
}
