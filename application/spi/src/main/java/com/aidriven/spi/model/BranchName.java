package com.aidriven.spi.model;

import java.util.Objects;

/**
 * Domain value object representing a branch name in a repository.
 * Provides validation and type safety for branch references.
 *
 * <p>
 * PSE-Grade Benefits:
 * - Type safety: prevents confusion with other string identifiers
 * - Validation: ensures valid branch names across platforms
 * - Immutable: thread-safe
 * </p>
 *
 * @since 1.0
 */
public final class BranchName {

    private static final int MAX_LENGTH = 255;
    private static final String[] RESERVED_NAMES = { "HEAD", "FETCH_HEAD", "MERGE_HEAD" };

    private final String value;

    /**
     * Creates a BranchName from a string value.
     *
     * @param value The branch name (e.g., "main", "feature/my-feature")
     * @return A new BranchName instance
     * @throws IllegalArgumentException if the value is invalid
     */
    public static BranchName of(String value) {
        validate(value);
        return new BranchName(value);
    }

    /**
     * Attempts to parse a branch name, returning null if invalid.
     *
     * @param value The branch name string
     * @return BranchName or null if invalid
     */
    public static BranchName ofNullable(String value) {
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
            throw new IllegalArgumentException("BranchName cannot be null or blank");
        }

        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("BranchName exceeds max length %d", MAX_LENGTH));
        }

        // Check for reserved names
        for (String reserved : RESERVED_NAMES) {
            if (value.equalsIgnoreCase(reserved)) {
                throw new IllegalArgumentException(
                        String.format("BranchName '%s' is reserved", value));
            }
        }

        // Ensure no control characters or double slashes
        if (value.contains("..") || value.contains("@{") || value.contains("\n")) {
            throw new IllegalArgumentException(
                    String.format("BranchName contains invalid sequences: '%s'", value));
        }

        // Must not start or end with /
        if (value.startsWith("/") || value.endsWith("/")) {
            throw new IllegalArgumentException(
                    String.format("BranchName must not start or end with '/': '%s'", value));
        }
    }

    private BranchName(String value) {
        this.value = value;
    }

    /**
     * Returns the string representation of this branch name.
     *
     * @return the branch name
     */
    public String value() {
        return value;
    }

    /**
     * Alias for value() for consistent domain naming.
     *
     * @return the branch name
     */
    public String name() {
        return value;
    }

    /**
     * Checks if this is a main/master branch.
     *
     * @return true if branch name matches common main branch patterns
     */
    public boolean isMainBranch() {
        return value.equalsIgnoreCase("main") || value.equalsIgnoreCase("master");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        BranchName that = (BranchName) obj;
        // Git branch names are case-sensitive, but some platforms normalize them
        return value.equals(that.value);
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
