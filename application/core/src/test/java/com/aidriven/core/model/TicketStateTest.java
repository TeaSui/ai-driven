package com.aidriven.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TicketStateTest {

    @Test
    void createPk_formatsCorrectly() {
        assertEquals("TICKET#acme#123", TicketState.createPk("acme", "123"));
    }

    @Test
    void should_create_current_state_sk() {
        String sk = TicketState.createCurrentStateSk();

        assertEquals("STATE#CURRENT", sk);
    }

    @Test
    void should_create_current_state_sk_always_same() {
        String sk1 = TicketState.createCurrentStateSk();
        String sk2 = TicketState.createCurrentStateSk();

        assertEquals(sk1, sk2);
    }

    @Test
    void should_create_timestamped_state_sk() {
        Instant now = Instant.parse("2026-02-01T10:00:00Z");

        String sk = TicketState.createStateSk(now);

        assertEquals("STATE#2026-02-01T10:00:00Z", sk);
    }

    @Test
    void should_create_idempotency_sk() {
        String sk = TicketState.createIdempotencySk("event-123");

        assertEquals("IDEMPOTENCY#event-123", sk);
    }

    @Test
    void should_create_status_gsi_pk() {
        String gsiPk = TicketState.createStatusGsi1Pk(ProcessingStatus.IN_PROGRESS);

        assertEquals("STATUS#IN_PROGRESS", gsiPk);
    }

    @Test
    void should_create_test_completed_status_gsi_pk() {
        String gsiPk = TicketState.createStatusGsi1Pk(ProcessingStatus.TEST_COMPLETED);

        assertEquals("STATUS#TEST_COMPLETED", gsiPk);
    }
}
