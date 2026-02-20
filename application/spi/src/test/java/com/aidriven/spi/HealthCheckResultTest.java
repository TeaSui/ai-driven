package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckResultTest {

    @Test
    void healthy_factory_creates_healthy_result() {
        HealthCheckResult result = HealthCheckResult.healthy();
        assertTrue(result.isHealthy());
        assertEquals(HealthCheckResult.Status.HEALTHY, result.status());
        assertEquals("OK", result.message());
        assertNotNull(result.checkedAt());
    }

    @Test
    void healthy_with_message() {
        HealthCheckResult result = HealthCheckResult.healthy("All good");
        assertTrue(result.isHealthy());
        assertEquals("All good", result.message());
    }

    @Test
    void degraded_factory() {
        HealthCheckResult result = HealthCheckResult.degraded("Slow response");
        assertFalse(result.isHealthy());
        assertEquals(HealthCheckResult.Status.DEGRADED, result.status());
        assertEquals("Slow response", result.message());
    }

    @Test
    void degraded_with_details() {
        HealthCheckResult result = HealthCheckResult.degraded("Slow", Map.of("latencyMs", 5000));
        assertEquals(5000, result.details().get("latencyMs"));
    }

    @Test
    void unhealthy_factory() {
        HealthCheckResult result = HealthCheckResult.unhealthy("Connection refused");
        assertFalse(result.isHealthy());
        assertEquals(HealthCheckResult.Status.UNHEALTHY, result.status());
    }

    @Test
    void unhealthy_with_cause() {
        RuntimeException cause = new RuntimeException("timeout");
        HealthCheckResult result = HealthCheckResult.unhealthy("Failed", cause);
        assertFalse(result.isHealthy());
        assertEquals("RuntimeException", result.details().get("error"));
        assertEquals("timeout", result.details().get("errorMessage"));
    }
}
