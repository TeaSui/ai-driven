package com.aidriven.core.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Hystrix-inspired circuit breaker for resilience.
 * Prevents cascading failures by failing fast when a service is down.
 *
 * <p>
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Service appears down, fail fast (no requests sent)
 * - HALF_OPEN: Testing if service recovered, limited requests allowed
 *
 * <p>
 * Configuration:
 * - failureThreshold: Number of failures before opening circuit (default: 5)
 * - successThreshold: Number of successes in HALF_OPEN before closing (default: 2)
 * - timeoutDuration: How long to wait in OPEN before trying HALF_OPEN (default: 30s)
 *
 * @since 1.0
 */
@Slf4j
public class CircuitBreaker {

    public enum State {
        CLOSED,      // Normal operation
        OPEN,        // Failing fast
        HALF_OPEN    // Testing recovery
    }

    private final String name;
    private final CircuitBreakerConfig config;
    private final AtomicReference<State> state;
    private final CircuitBreakerMetrics metrics;
    private volatile Instant lastFailureTime;

    /**
     * Creates a circuit breaker with default configuration.
     *
     * @param name Identifier for this circuit breaker (e.g., "Claude API")
     */
    public CircuitBreaker(String name) {
        this(name, CircuitBreakerConfig.defaults());
    }

    /**
     * Creates a circuit breaker with custom configuration.
     *
     * @param name Identifier for this circuit breaker
     * @param config Configuration parameters
     */
    public CircuitBreaker(String name, CircuitBreakerConfig config) {
        this.name = Objects.requireNonNull(name, "name");
        this.config = Objects.requireNonNull(config, "config");
        this.state = new AtomicReference<>(State.CLOSED);
        this.metrics = new CircuitBreakerMetrics();
        this.lastFailureTime = null;
    }

    /**
     * Creates a circuit breaker with simple configuration (backward compatible constructor).
     *
     * @param name Identifier for this circuit breaker
     * @param failureThreshold Number of failures before opening circuit
     * @param resetTimeoutMs Time in milliseconds to wait in OPEN before trying HALF_OPEN
     */
    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMs) {
        this.name = Objects.requireNonNull(name, "name");
        this.config = CircuitBreakerConfig.builder()
                .failureThreshold(failureThreshold)
                .successThreshold(2)
                .timeoutDuration(Duration.ofMillis(resetTimeoutMs))
                .build();
        this.state = new AtomicReference<>(State.CLOSED);
        this.metrics = new CircuitBreakerMetrics();
        this.lastFailureTime = null;
    }

    /**
     * Executes an operation through the circuit breaker.
     *
     * @param operation The operation to execute
     * @return The operation result
     * @throws CircuitBreakerOpenException if circuit is open
     * @throws Exception if operation fails
     */
    public <T> T execute(CircuitBreakerOperation<T> operation) throws Exception {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                return executeClosed(operation);
            case OPEN:
                return executeOpen(operation);
            case HALF_OPEN:
                return executeHalfOpen(operation);
            default:
                throw new IllegalStateException("Unknown state: " + currentState);
        }
    }

    private <T> T executeClosed(CircuitBreakerOperation<T> operation) throws Exception {
        try {
            T result = operation.execute();
            metrics.recordSuccess();
            return result;
        } catch (Exception e) {
            metrics.recordFailure();
            lastFailureTime = Instant.now();

            if (metrics.getFailureCount() >= config.getFailureThreshold()) {
                transitionToOpen();
                log.warn("Circuit breaker '{}' opened after {} failures", name, config.getFailureThreshold());
            }
            throw e;
        }
    }

    private <T> T executeOpen(CircuitBreakerOperation<T> operation) throws Exception {
        if (shouldAttemptReset()) {
            transitionToHalfOpen();
            return executeHalfOpen(operation);
        }

        throw new CircuitBreakerOpenException("Circuit breaker '" + name + "' is OPEN");
    }

    private <T> T executeHalfOpen(CircuitBreakerOperation<T> operation) throws Exception {
        try {
            T result = operation.execute();
            metrics.recordHalfOpenSuccess();

            if (metrics.getHalfOpenSuccessCount() >= config.getSuccessThreshold()) {
                transitionToClosed();
                log.info("Circuit breaker '{}' closed after {} successes in HALF_OPEN", name,
                        config.getSuccessThreshold());
            }
            return result;
        } catch (Exception e) {
            metrics.recordHalfOpenFailure();
            transitionToOpen();
            log.warn("Circuit breaker '{}' reopened after failure in HALF_OPEN", name);
            throw e;
        }
    }

    private boolean shouldAttemptReset() {
        if (lastFailureTime == null) {
            return true;
        }
        Instant now = Instant.now();
        return now.isAfter(lastFailureTime.plus(config.getTimeoutDuration()));
    }

    private void transitionToOpen() {
        state.set(State.OPEN);
        metrics.recordTransition(State.OPEN);
    }

    private void transitionToHalfOpen() {
        state.set(State.HALF_OPEN);
        metrics.recordTransition(State.HALF_OPEN);
        metrics.resetHalfOpenCounters();
    }

    private void transitionToClosed() {
        state.set(State.CLOSED);
        metrics.recordTransition(State.CLOSED);
        metrics.reset();
    }

    /**
     * Gets the current state.
     *
     * @return current state
     */
    public State getState() {
        return state.get();
    }

    /**
     * Gets circuit breaker metrics.
     *
     * @return metrics
     */
    public CircuitBreakerMetrics getMetrics() {
        return metrics;
    }

    /**
     * Resets the circuit breaker to CLOSED state.
     * Useful for testing or manual recovery.
     */
    public void reset() {
        transitionToClosed();
        log.info("Circuit breaker '{}' manually reset to CLOSED", name);
    }

    // ==================== Backward-compatible methods for legacy API ====================

    /**
     * Checks if a request is allowed in the current state.
     * Backward-compatible method for legacy API.
     *
     * @return true if request is allowed, false if circuit is open
     */
    public boolean allowRequest() {
        State currentState = state.get();

        if (currentState == State.CLOSED) {
            return true;
        }

        if (currentState == State.OPEN) {
            if (shouldAttemptReset()) {
                // Time to try moving to HALF_OPEN
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("CircuitBreaker '{}' moved to HALF_OPEN", name);
                    return true;
                }
            }
            return false;
        }

        // HALF_OPEN: allow request to test recovery
        return true;
    }

    /**
     * Records a successful operation.
     * Backward-compatible method for legacy API.
     * In CLOSED state: resets failure count.
     * In HALF_OPEN state: checks if enough successes to close the circuit.
     */
    public void recordSuccess() {
        State currentState = state.get();

        if (currentState == State.CLOSED) {
            // Reset failure count on success in normal operation
            metrics.reset();
            log.debug("CircuitBreaker '{}' recorded success, failure count reset", name);
        } else if (currentState == State.HALF_OPEN) {
            // In HALF_OPEN, track successes
            metrics.recordHalfOpenSuccess();

            if (metrics.getHalfOpenSuccessCount() >= config.getSuccessThreshold()) {
                transitionToClosed();
                log.info("CircuitBreaker '{}' recovered to CLOSED after {} HALF_OPEN successes",
                        name, metrics.getHalfOpenSuccessCount());
            }
        }
    }

    /**
     * Records a failed operation.
     * Backward-compatible method for legacy API.
     * Opens the circuit if failure threshold is reached.
     */
    public void recordFailure() {
        lastFailureTime = Instant.now();
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // Any failure in HALF_OPEN immediately opens the circuit
            transitionToOpen();
            log.warn("CircuitBreaker '{}' reopened after failure in HALF_OPEN", name);
        } else if (currentState == State.CLOSED) {
            // Track failures in CLOSED state
            metrics.recordFailure();

            if (metrics.getFailureCount() >= config.getFailureThreshold()) {
                transitionToOpen();
                log.warn("CircuitBreaker '{}' TRIPPED to OPEN after {} failures",
                        name, metrics.getFailureCount());
            }
        }
        // If already OPEN, just update the failure time
    }

    /**
     * Operation that can be executed through the circuit breaker.
     *
     * @param <T> Result type
     */
    @FunctionalInterface
    public interface CircuitBreakerOperation<T> {
        T execute() throws Exception;
    }
}

