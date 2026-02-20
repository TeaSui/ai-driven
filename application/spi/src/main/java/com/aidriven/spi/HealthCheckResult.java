package com.aidriven.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Result of a module health check.
 */
public record HealthCheckResult(
        Status status,
        String message,
        Map<String, Object> details,
        Instant checkedAt) {

    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }

    public static HealthCheckResult healthy() {
        return new HealthCheckResult(Status.HEALTHY, "OK", Map.of(), Instant.now());
    }

    public static HealthCheckResult healthy(String message) {
        return new HealthCheckResult(Status.HEALTHY, message, Map.of(), Instant.now());
    }

    public static HealthCheckResult degraded(String message) {
        return new HealthCheckResult(Status.DEGRADED, message, Map.of(), Instant.now());
    }

    public static HealthCheckResult degraded(String message, Map<String, Object> details) {
        return new HealthCheckResult(Status.DEGRADED, message, details, Instant.now());
    }

    public static HealthCheckResult unhealthy(String message) {
        return new HealthCheckResult(Status.UNHEALTHY, message, Map.of(), Instant.now());
    }

    public static HealthCheckResult unhealthy(String message, Throwable cause) {
        return new HealthCheckResult(Status.UNHEALTHY, message,
                Map.of("error", cause.getClass().getSimpleName(), "errorMessage", cause.getMessage()),
                Instant.now());
    }

    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }
}
