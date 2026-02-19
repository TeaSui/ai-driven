package com.aidriven.spi;

/**
 * SPI interface for pluggable service modules.
 *
 * <p>Each integration (Jira, Bitbucket, GitHub, Claude, Datadog, Slack, etc.)
 * implements this interface. Modules are discovered at runtime via
 * {@link java.util.ServiceLoader} or explicit registration.</p>
 *
 * <p>This enables per-tenant module composition: Company A uses Jira + GitHub,
 * Company B uses Linear + Bitbucket, Company C uses Jira + GitHub + Datadog.</p>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>{@link #id()} — unique module identifier</li>
 *   <li>{@link #initialize(ModuleContext)} — called once on startup with tenant config</li>
 *   <li>{@link #isHealthy()} — periodic health checks</li>
 *   <li>{@link #shutdown()} — graceful cleanup</li>
 * </ol>
 */
public interface ServiceModule {

    /**
     * Unique identifier for this module (e.g., "jira", "github", "datadog").
     * Used as the key in tenant configuration maps.
     */
    String id();

    /**
     * Human-readable display name.
     */
    String displayName();

    /**
     * Module category for grouping.
     */
    ModuleCategory category();

    /**
     * Initializes the module with tenant-specific configuration.
     *
     * @param context Module context containing secrets, config, and shared services
     * @throws ModuleInitializationException if initialization fails
     */
    void initialize(ModuleContext context) throws ModuleInitializationException;

    /**
     * Returns true if the module is initialized and operational.
     */
    boolean isHealthy();

    /**
     * Gracefully shuts down the module, releasing resources.
     */
    default void shutdown() {
        // Default no-op for stateless modules
    }

    /**
     * Returns the set of configuration keys this module requires.
     * Used for validation before initialization.
     */
    default java.util.Set<String> requiredConfigKeys() {
        return java.util.Set.of();
    }
}
