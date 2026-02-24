package com.aidriven.core.resilience;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Exponential backoff retry strategy with jitter.
 * Implements full jitter algorithm to prevent thundering herd.
 *
 * <p>
 * Delay calculation: random(0, min(cap, base * 2^attempt))
 * This spreads retry attempts over time, preventing synchronized retries.
 *
 * @since 1.0
 */
@Getter
@Builder(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ExponentialBackoffRetry implements RetryStrategy {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(100);
    private static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(30);
    private static final double DEFAULT_MULTIPLIER = 2.0;

    /**
     * Maximum number of attempts (includes initial attempt).
     * Default: 5 (1 initial + 4 retries)
     */
    private final int maxAttempts;

    /**
     * Initial delay before first retry.
     * Default: 100ms
     */
    private final Duration initialDelay;

    /**
     * Maximum delay cap between retries.
     * Default: 30s
     */
    private final Duration maxDelay;

    /**
     * Multiplier for exponential backoff.
     * Default: 2.0 (delay doubles each retry)
     */
    private final double multiplier;

    /**
     * Exception types that should NOT be retried (fast-fail).
     * Empty set = retry all exceptions.
     */
    private final Set<Class<? extends Exception>> noRetryExceptions;

    /**
     * Creates retry strategy with default configuration.
     * - maxAttempts: 5
     * - initialDelay: 100ms
     * - maxDelay: 30s
     * - multiplier: 2.0
     *
     * @return configured strategy
     */
    public static ExponentialBackoffRetry defaults() {
        return ExponentialBackoffRetry.builder()
                .maxAttempts(DEFAULT_MAX_ATTEMPTS)
                .initialDelay(DEFAULT_INITIAL_DELAY)
                .maxDelay(DEFAULT_MAX_DELAY)
                .multiplier(DEFAULT_MULTIPLIER)
                .noRetryExceptions(new HashSet<>())
                .build();
    }

    /**
     * Creates aggressive retry strategy (many retries, slow backoff).
     *
     * @return configured strategy
     */
    public static ExponentialBackoffRetry aggressive() {
        return ExponentialBackoffRetry.builder()
                .maxAttempts(8)
                .initialDelay(Duration.ofMillis(50))
                .maxDelay(Duration.ofMinutes(1))
                .multiplier(2.0)
                .noRetryExceptions(new HashSet<>())
                .build();
    }

    /**
     * Creates conservative retry strategy (few retries, fast backoff).
     *
     * @return configured strategy
     */
    public static ExponentialBackoffRetry conservative() {
        return ExponentialBackoffRetry.builder()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(5))
                .multiplier(2.0)
                .noRetryExceptions(new HashSet<>())
                .build();
    }

    @Override
    public Duration getDelay(int attemptNumber, Exception lastException) {
        if (attemptNumber <= 1) {
            return Duration.ZERO;
        }

        // Calculate base delay: initialDelay * multiplier^(attemptNumber - 1)
        long delayMs = (long) (initialDelay.toMillis() * Math.pow(multiplier, attemptNumber - 2));
        long cappedDelayMs = Math.min(delayMs, maxDelay.toMillis());

        // Full jitter: random(0, cappedDelay)
        long jitterDelayMs = (long) (Math.random() * cappedDelayMs);

        return Duration.ofMillis(jitterDelayMs);
    }

    @Override
    public boolean shouldRetry(int attemptNumber, Exception lastException) {
        if (attemptNumber >= maxAttempts) {
            return false;
        }

        // Don't retry if exception is in no-retry list
        if (noRetryExceptions.contains(lastException.getClass())) {
            return false;
        }

        return true;
    }

    @Override
    public int getMaxAttempts() {
        return maxAttempts;
    }
}

