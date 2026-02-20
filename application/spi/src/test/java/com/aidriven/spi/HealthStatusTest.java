package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthStatusTest {

    @Test
    void should_create_healthy_status() {
        HealthStatus status = HealthStatus.healthy("All good");

        assertTrue(status.isHealthy());
        assertEquals(HealthStatus.Status.HEALTHY, status.status());
        assertEquals("All good", status.message());
        assertNotNull(status.checkedAt());
    }

    @Test
    void should_create_healthy_with_details() {
        HealthStatus status = HealthStatus.healthy("OK", Map.of("latency", 42));

        assertTrue(status.isHealthy());
        assertEquals(42, status.details().get("latency"));
    }

    @Test
    void should_create_degraded_status() {
        HealthStatus status = HealthStatus.degraded("Slow");

        assertFalse(status.isHealthy());
        assertEquals(HealthStatus.Status.DEGRADED, status.status());
    }

    @Test
    void should_create_unhealthy_status() {
        HealthStatus status = HealthStatus.unhealthy("Connection refused");

        assertFalse(status.isHealthy());
        assertEquals(HealthStatus.Status.UNHEALTHY, status.status());
    }

    @Test
    void should_create_not_initialized_status() {
        HealthStatus status = HealthStatus.notInitialized();

        assertFalse(status.isHealthy());
        assertEquals(HealthStatus.Status.NOT_INITIALIZED, status.status());
    }
}
