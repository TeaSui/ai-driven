package com.aidriven.core.resilience;

import java.time.Duration;

/**
 * Strategy for retrying failed operations.
 * Implementations define different backoff patterns (fixed, exponential, etc.).
 *
 * @since 1.0
 */
public interface RetryStrategy {

    /**
     * Calculates delay before next retry attempt.
     *
     * @param attemptNumber Current attempt number (1-based)
     * @param lastException The exception from the last attempt
     * @return Duration to wait before retrying, or Duration.ZERO to retry immediately
     */
    Duration getDelay(int attemptNumber, Exception lastException);

    /**
     * Determines if an operation should be retried.
     *
     * @param attemptNumber Current attempt number (1-based)
     * @param lastException The exception from the last attempt
     * @return true if should retry, false if should give up
     */
    boolean shouldRetry(int attemptNumber, Exception lastException);

    /**
     * Gets the maximum number of retry attempts.
     *
     * @return max attempts (including initial attempt)
     */
    int getMaxAttempts();
}

