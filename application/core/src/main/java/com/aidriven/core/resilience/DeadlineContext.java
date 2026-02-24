package com.aidriven.core.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Thread-local context tracking operation deadline.
 * Enables graceful timeout handling and deadline propagation.
 *
 * <p>
 * Usage:
 * - Set deadline at handler entry: DeadlineContext.set(Instant.now().plus(Duration.ofSeconds(30)))
 * - Check before expensive operations: if (DeadlineContext.isDeadlineApproaching()) abort()
 * - Clean up: DeadlineContext.clear()
 *
 * @since 1.0
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DeadlineContext {

    private static final ThreadLocal<DeadlineContext> CONTEXT = new ThreadLocal<>();
    private static final Duration DEFAULT_BUFFER = Duration.ofSeconds(5);

    /**
     * Absolute deadline timestamp.
     */
    private final Instant deadline;

    /**
     * Time buffer before deadline to consider it "approaching".
     */
    private final Duration warningBuffer;

    /**
     * Sets deadline for current thread.
     *
     * @param deadline Absolute deadline instant
     */
    public static void set(Instant deadline) {
        set(deadline, DEFAULT_BUFFER);
    }

    /**
     * Sets deadline with custom warning buffer.
     *
     * @param deadline Absolute deadline instant
     * @param warningBuffer Duration before deadline to warn
     */
    public static void set(Instant deadline, Duration warningBuffer) {
        CONTEXT.set(new DeadlineContext(deadline, warningBuffer));
    }

    /**
     * Gets current deadline context if set.
     *
     * @return deadline context or empty
     */
    public static Optional<DeadlineContext> get() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /**
     * Clears deadline context.
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Checks if deadline has passed.
     *
     * @return true if current time is past deadline
     */
    public boolean isDeadlinePassed() {
        return Instant.now().isAfter(deadline);
    }

    /**
     * Checks if deadline is approaching (within warning buffer).
     *
     * @return true if deadline is within warning buffer
     */
    public boolean isDeadlineApproaching() {
        Instant warningPoint = deadline.minus(warningBuffer);
        return Instant.now().isAfter(warningPoint);
    }

    /**
     * Gets remaining time until deadline.
     *
     * @return remaining duration, or negative if deadline passed
     */
    public Duration getRemainingTime() {
        return Duration.between(Instant.now(), deadline);
    }

    /**
     * Throws exception if deadline has passed.
     *
     * @throws DeadlineExceededException if deadline passed
     */
    public void throwIfDeadlinePassed() throws DeadlineExceededException {
        if (isDeadlinePassed()) {
            throw new DeadlineExceededException(
                    String.format("Deadline exceeded. Remaining: %s", getRemainingTime()));
        }
    }

    /**
     * Throws exception if deadline is approaching.
     *
     * @throws DeadlineApproachingException if deadline approaching
     */
    public void throwIfDeadlineApproaching() throws DeadlineApproachingException {
        if (isDeadlineApproaching()) {
            throw new DeadlineApproachingException(
                    String.format("Deadline approaching. Remaining: %s", getRemainingTime()));
        }
    }

    /**
     * Gets current deadline if set, or empty if not.
     * Convenience method that gets deadline from thread-local context.
     *
     * @return Optional deadline
     */
    public static Optional<Instant> getCurrentDeadline() {
        return get().map(ctx -> ctx.deadline);
    }

    /**
     * Checks if deadline is set and has passed.
     *
     * @return true if deadline set and passed
     */
    public static boolean hasDeadlinePassed() {
        return get().map(DeadlineContext::isDeadlinePassed).orElse(false);
    }

    /**
     * Checks if deadline is set and approaching.
     *
     * @return true if deadline set and approaching
     */
    public static boolean isDeadlineApproachingNow() {
        return get().map(DeadlineContext::isDeadlineApproaching).orElse(false);
    }
}

