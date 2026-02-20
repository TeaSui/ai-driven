package com.aidriven.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Health status of a module.
 *
 * @param status    Overall status
 * @param message   Human-readable status message
 * @param details   Additional details (e.g., latency, version)
 * @param checkedAt When the health check was performed
 */
public record HealthStatus(
        Status status,
        String message,
        Map<String, Object> details,
        Instant checkedAt) {

    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        NOT_INITIALIZED
    }

    public static HealthStatus healthy(String message) {
        return new HealthStatus(Status.HEALTHY, message, Map.of(), Instant.now());
    }

    public static HealthStatus healthy(String message, Map<String, Object> details) {
        return new HealthStatus(Status.HEALTHY, message, details, Instant.now());
    }

    public static HealthStatus degraded(String message) {
        return new HealthStatus(Status.DEGRADED, message, Map.of(), Instant.now());
    }

    public static HealthStatus unhealthy(String message) {
        return new HealthStatus(Status.UNHEALTHY, message, Map.of(), Instant.now());
    }

    public static HealthStatus notInitialized() {
        return new HealthStatus(Status.NOT_INITIALIZED, "Module not initialized", Map.of(), Instant.now());
    }

    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }
}
