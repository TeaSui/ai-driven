package com.aidriven.spi;

/**
 * Service Provider Interface for pluggable AI-Driven modules.
 *
 * <p>Each module (source control, issue tracker, AI provider, etc.) implements
 * this interface and is discovered via {@link java.util.ServiceLoader}.
 * This enables companies to bring their own integrations without modifying core code.</p>
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #id()} — unique module identifier (e.g., "bitbucket", "jira", "claude")</li>
 *   <li>{@link #type()} — module category for registry lookup</li>
 *   <li>{@link #initialize(ModuleContext)} — called once during startup with tenant config</li>
 *   <li>{@link #healthCheck()} — periodic health verification</li>
 *   <li>{@link #shutdown()} — graceful cleanup</li>
 * </ol>
 */
public interface AiDrivenModule {

    /**
     * Unique identifier for this module (e.g., "bitbucket", "github", "jira", "claude").
     * Used as a registry key and in configuration.
     */
    String id();

    /**
     * Module type/category for grouping (e.g., "source-control", "issue-tracker", "ai-provider").
     */
    ModuleType type();

    /**
     * Human-readable display name.
     */
    default String displayName() {
        return id();
    }

    /**
     * Semantic version of this module.
     */
    default String version() {
        return "1.0.0";
    }

    /**
     * Initialize the module with tenant-specific configuration.
     *
     * @param context Module context containing configuration and shared services
     * @throws ModuleInitializationException if initialization fails
     */
    void initialize(ModuleContext context) throws ModuleInitializationException;

    /**
     * Check if the module is healthy and operational.
     *
     * @return Health check result
     */
    HealthCheckResult healthCheck();

    /**
     * Gracefully shut down the module, releasing resources.
     */
    default void shutdown() {
        // Default no-op for stateless modules
    }

    /**
     * Whether this module is currently initialized and ready.
     */
    boolean isReady();
}
