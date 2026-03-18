package com.aidriven.core.context;

/**
 * Determines the strategy for gathering repository code context.
 *
 * <p>Previously nested inside {@code AppConfig}. Extracted as a standalone
 * enum to decouple context strategy selection from the application
 * configuration monolith.</p>
 *
 * @since 1.0
 */
public enum ContextMode {
    /**
     * Download and analyze the full repository content.
     * Higher cost but provides complete context.
     */
    FULL_REPO,

    /**
     * Use incremental/smart context: only fetch relevant files
     * based on ticket content and change history.
     * Lower cost, suitable for most tickets.
     */
    INCREMENTAL
}
