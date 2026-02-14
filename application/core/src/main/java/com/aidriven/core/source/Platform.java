package com.aidriven.core.source;

/**
 * Supported source control platforms.
 */
public enum Platform {
    BITBUCKET,
    GITHUB;

    /**
     * Resolves platform from a string value (case-insensitive).
     * Returns null if the value is not recognized.
     */
    public static Platform fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Detects platform from a repository URL domain.
     */
    public static Platform fromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String lower = url.toLowerCase();
        if (lower.contains("github.com")) {
            return GITHUB;
        }
        if (lower.contains("bitbucket.org") || lower.contains("bitbucket.com")) {
            return BITBUCKET;
        }
        return null;
    }
}
