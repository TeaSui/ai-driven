package com.aidriven.core.model.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Domain value object representing a repository identifier (owner + name).
 * Enforces validation and provides type safety for repository references.
 *
 * <p>
 * PSE-Grade Benefits:
 * - Type safety: prevents mixing owner/repo in wrong order
 * - Validation: ensures valid platform names
 * - Immutable: thread-safe
 * - Clear semantics: `new RepositoryIdentifier("torvalds", "linux")` vs raw strings
 * </p>
 *
 * @since 1.0
 */
public final class RepositoryIdentifier {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");
    private static final int MAX_NAME_LENGTH = 255;

    private final String owner;
    private final String name;

    /**
     * Creates a RepositoryIdentifier from owner and name.
     *
     * @param owner Repository owner (e.g., "torvalds")
     * @param name  Repository name (e.g., "linux")
     * @return A new RepositoryIdentifier instance
     * @throws IllegalArgumentException if owner or name is invalid
     */
    public static RepositoryIdentifier of(String owner, String name) {
        validateOwner(owner);
        validateName(name);
        return new RepositoryIdentifier(owner, name);
    }

    /**
     * Parses a repository identifier from "owner/name" format.
     *
     * @param fullPath The repository path (e.g., "torvalds/linux")
     * @return A new RepositoryIdentifier instance
     * @throws IllegalArgumentException if format is invalid
     */
    public static RepositoryIdentifier parse(String fullPath) {
        if (fullPath == null || fullPath.isBlank()) {
            throw new IllegalArgumentException("RepositoryIdentifier path cannot be null or blank");
        }

        String[] parts = fullPath.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    String.format("RepositoryIdentifier must be in 'owner/name' format, got '%s'", fullPath));
        }

        return of(parts[0], parts[1]);
    }

    private static void validateOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Repository owner cannot be null or blank");
        }
        if (owner.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Repository owner exceeds max length %d", MAX_NAME_LENGTH));
        }
        if (!NAME_PATTERN.matcher(owner).matches()) {
            throw new IllegalArgumentException(
                    String.format("Repository owner contains invalid characters: '%s'", owner));
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Repository name cannot be null or blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Repository name exceeds max length %d", MAX_NAME_LENGTH));
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    String.format("Repository name contains invalid characters: '%s'", name));
        }
    }

    private RepositoryIdentifier(String owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    /**
     * Gets the repository owner.
     *
     * @return the owner
     */
    public String owner() {
        return owner;
    }

    /**
     * Gets the repository name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the full repository path in "owner/name" format.
     *
     * @return the full path
     */
    public String fullPath() {
        return owner + "/" + name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        RepositoryIdentifier that = (RepositoryIdentifier) obj;
        return owner.equals(that.owner) && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, name);
    }

    @Override
    public String toString() {
        return fullPath();
    }
}

