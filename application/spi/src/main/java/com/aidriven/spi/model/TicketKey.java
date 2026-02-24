package com.aidriven.spi.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Domain value object representing a Jira ticket key (e.g., "PROJ-123").
 * Enforces format validation and provides type safety throughout the codebase.
 *
 * <p>
 * PSE-Grade Benefits:
 * - Type safety: eliminates stringly-typed method signatures
 * - Validation at creation: prevents invalid states
 * - Self-documenting: code intent is clear (TicketKey vs String)
 * - Immutable: thread-safe by design
 * </p>
 *
 * @since 1.0
 */
public final class TicketKey {

    private static final Pattern TICKET_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]*-\\d+$");
    private static final int MIN_LENGTH = 3; // e.g., "A-1"
    private static final int MAX_LENGTH = 32; // reasonable upper bound for platform support

    private final String value;

    /**
     * Creates a TicketKey from a string value.
     *
     * @param value The ticket key string (e.g., "PROJ-123")
     * @return A new TicketKey instance
     * @throws IllegalArgumentException if the value is invalid
     */
    public static TicketKey of(String value) {
        validate(value);
        return new TicketKey(value);
    }

    /**
     * Attempts to parse a ticket key, returning null if invalid.
     * Useful for non-strict parsing scenarios.
     *
     * @param value The ticket key string
     * @return TicketKey or null if invalid
     */
    public static TicketKey ofNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return of(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void validate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TicketKey cannot be null or blank");
        }

        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("TicketKey length must be between %d and %d, got %d",
                            MIN_LENGTH, MAX_LENGTH, value.length()));
        }

        if (!TICKET_KEY_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    String.format("TicketKey must match pattern [A-Z][A-Z0-9]*-\\d+, got '%s'", value));
        }
    }

    private TicketKey(String value) {
        this.value = value;
    }

    /**
     * Returns the string representation of this ticket key.
     *
     * @return the ticket key string
     */
    public String value() {
        return value;
    }

    /**
     * Extracts the project key prefix (e.g., "PROJ" from "PROJ-123").
     *
     * @return the project key
     */
    public String projectKey() {
        return value.substring(0, value.indexOf('-'));
    }

    /**
     * Extracts the ticket number (e.g., "123" from "PROJ-123").
     *
     * @return the ticket number
     */
    public long ticketNumber() {
        return Long.parseLong(value.substring(value.indexOf('-') + 1));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        TicketKey ticketKey = (TicketKey) obj;
        return value.equals(ticketKey.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
