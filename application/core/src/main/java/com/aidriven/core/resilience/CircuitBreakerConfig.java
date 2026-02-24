package com.aidriven.core.resilience;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Configuration for CircuitBreaker.
 * Immutable value object holding all tunable parameters.
 *
 * @since 1.0
 */
@Getter
@Builder(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CircuitBreakerConfig {

    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final int DEFAULT_SUCCESS_THRESHOLD = 2;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Number of failures before opening circuit.
     */
    private final int failureThreshold;

    /**
     * Number of successes in HALF_OPEN before closing circuit.
     */
    private final int successThreshold;

    /**
     * Duration to wait in OPEN state before trying HALF_OPEN.
     */
    private final Duration timeoutDuration;

    /**
     * Creates default configuration.
     * - failureThreshold: 5
     * - successThreshold: 2
     * - timeout: 30s
     *
     * @return default config
     */
    public static CircuitBreakerConfig defaults() {
        return CircuitBreakerConfig.builder()
                .failureThreshold(DEFAULT_FAILURE_THRESHOLD)
                .successThreshold(DEFAULT_SUCCESS_THRESHOLD)
                .timeoutDuration(DEFAULT_TIMEOUT)
                .build();
    }

    /**
     * Creates aggressive configuration (faster failure detection).
     * - failureThreshold: 3
     * - successThreshold: 1
     * - timeout: 10s
     *
     * @return aggressive config
     */
    public static CircuitBreakerConfig aggressive() {
        return CircuitBreakerConfig.builder()
                .failureThreshold(3)
                .successThreshold(1)
                .timeoutDuration(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Creates lenient configuration (slower failure detection).
     * - failureThreshold: 10
     * - successThreshold: 5
     * - timeout: 60s
     *
     * @return lenient config
     */
    public static CircuitBreakerConfig lenient() {
        return CircuitBreakerConfig.builder()
                .failureThreshold(10)
                .successThreshold(5)
                .timeoutDuration(Duration.ofSeconds(60))
                .build();
    }
}

