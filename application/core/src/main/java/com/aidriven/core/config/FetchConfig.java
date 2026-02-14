package com.aidriven.core.config;

/**
 * Configuration for fetching repository content and building context.
 */
public record FetchConfig(
        int maxFileSizeChars,
        long maxTotalContextChars,
        long maxFileSizeBytes,
        String contextMode) {
}
