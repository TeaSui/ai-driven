package com.aidriven.core.resilience;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes operations with retry logic.
 * Handles backoff delays, state tracking, and logging.
 *
 * @since 1.0
 */
@Slf4j
public class RetryExecutor {

    private final RetryStrategy strategy;

    /**
     * Creates executor with given strategy.
     *
     * @param strategy Retry strategy to use
     */
    public RetryExecutor(RetryStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Executes operation with retries.
     *
     * @param <T> Result type
     * @param operation Operation to execute
     * @param operationName Name for logging
     * @return Operation result
     * @throws Exception if all retries exhausted
     */
    public <T> T execute(RetryableOperation<T> operation, String operationName) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= strategy.getMaxAttempts(); attempt++) {
            try {
                log.debug("Executing {} (attempt {}/{})", operationName, attempt, strategy.getMaxAttempts());
                return operation.execute();
            } catch (Exception e) {
                lastException = e;

                if (!strategy.shouldRetry(attempt, e)) {
                    log.warn("Not retrying {} after attempt {}: {}", operationName, attempt, e.getMessage());
                    throw e;
                }

                if (attempt < strategy.getMaxAttempts()) {
                    Duration delay = strategy.getDelay(attempt, e);
                    log.debug("Retrying {} after {} ms (attempt {}/{})", operationName,
                            delay.toMillis(), attempt, strategy.getMaxAttempts());

                    if (!delay.isZero()) {
                        Thread.sleep(delay.toMillis());
                    }
                }
            }
        }

        // All retries exhausted
        throw new RetryExhaustedException(
                String.format("All %d attempts exhausted for %s", strategy.getMaxAttempts(), operationName),
                lastException);
    }

    /**
     * Executes operation with retries and default naming.
     *
     * @param <T> Result type
     * @param operation Operation to execute
     * @return Operation result
     * @throws Exception if all retries exhausted
     */
    public <T> T execute(RetryableOperation<T> operation) throws Exception {
        return execute(operation, operation.getClass().getSimpleName());
    }

    /**
     * Operation that can be retried.
     *
     * @param <T> Result type
     */
    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
}

