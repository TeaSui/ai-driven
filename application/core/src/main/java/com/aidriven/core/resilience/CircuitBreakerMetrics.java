package com.aidriven.core.resilience;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics for CircuitBreaker monitoring.
 * Thread-safe tracking of circuit breaker state transitions and operation results.
 *
 * @since 1.0
 */
public class CircuitBreakerMetrics {

    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenFailureCount = new AtomicInteger(0);
    private final AtomicReference<Instant> lastStateTransition = new AtomicReference<>();
    private final AtomicReference<CircuitBreaker.State> lastState = new AtomicReference<>();
    private final AtomicLong openCount = new AtomicLong(0);

    /**
     * Records a successful operation.
     */
    public void recordSuccess() {
        successCount.incrementAndGet();
    }

    /**
     * Records a failed operation.
     */
    public void recordFailure() {
        failureCount.incrementAndGet();
    }

    /**
     * Records a successful operation in HALF_OPEN state.
     */
    public void recordHalfOpenSuccess() {
        halfOpenSuccessCount.incrementAndGet();
    }

    /**
     * Records a failed operation in HALF_OPEN state.
     */
    public void recordHalfOpenFailure() {
        halfOpenFailureCount.incrementAndGet();
    }

    /**
     * Records a state transition.
     *
     * @param newState The state being transitioned to
     */
    public void recordTransition(CircuitBreaker.State newState) {
        lastState.set(newState);
        lastStateTransition.set(Instant.now());

        if (newState == CircuitBreaker.State.OPEN) {
            openCount.incrementAndGet();
        }
    }

    /**
     * Resets all counters for CLOSED state.
     */
    public void reset() {
        successCount.set(0);
        failureCount.set(0);
        halfOpenSuccessCount.set(0);
        halfOpenFailureCount.set(0);
    }

    /**
     * Resets HALF_OPEN counters when transitioning away from HALF_OPEN.
     */
    public void resetHalfOpenCounters() {
        halfOpenSuccessCount.set(0);
        halfOpenFailureCount.set(0);
    }

    // Getters

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getFailureCount() {
        return failureCount.get();
    }

    public int getHalfOpenSuccessCount() {
        return halfOpenSuccessCount.get();
    }

    public int getHalfOpenFailureCount() {
        return halfOpenFailureCount.get();
    }

    public Instant getLastStateTransition() {
        return lastStateTransition.get();
    }

    public CircuitBreaker.State getLastState() {
        return lastState.get();
    }

    public long getOpenCount() {
        return openCount.get();
    }

    /**
     * Calculates current failure rate.
     *
     * @return failure rate (0.0 to 1.0)
     */
    public double getFailureRate() {
        long total = successCount.get() + failureCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) failureCount.get() / total;
    }

    @Override
    public String toString() {
        return String.format(
                "CircuitBreakerMetrics{successes=%d, failures=%d, failureRate=%.2f, opens=%d}",
                getSuccessCount(), getFailureCount(), getFailureRate(), getOpenCount());
    }
}

