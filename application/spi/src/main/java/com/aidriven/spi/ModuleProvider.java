package com.aidriven.spi;

/**
 * Service Provider Interface for pluggable modules.
 *
 * <p>Each module implements this interface and registers via
 * {@code META-INF/services/com.aidriven.spi.ModuleProvider}.
 * The {@link ModuleRegistry} discovers and loads all providers
 * at startup.</p>
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #descriptor()} — called during discovery to inspect capabilities</li>
 *   <li>{@link #initialize(TenantContext)} — called when a tenant activates this module</li>
 *   <li>{@link #healthCheck()} — called periodically to verify module health</li>
 *   <li>{@link #shutdown()} — called on graceful shutdown or tenant deactivation</li>
 * </ol>
 */
public interface ModuleProvider {

    /**
     * Returns the module descriptor with metadata and capabilities.
     * Must be callable without initialization (used for discovery).
     */
    ModuleDescriptor descriptor();

    /**
     * Initializes the module with tenant-specific configuration.
     *
     * @param context Tenant context with configuration and secrets
     * @throws ModuleInitializationException if initialization fails
     */
    void initialize(TenantContext context) throws ModuleInitializationException;

    /**
     * Checks if the module is healthy and operational.
     *
     * @return Health status
     */
    HealthStatus healthCheck();

    /**
     * Gracefully shuts down the module, releasing resources.
     */
    default void shutdown() {
        // Default no-op for stateless modules
    }

    /**
     * Returns whether this module has been initialized.
     */
    default boolean isInitialized() {
        return false;
    }
}
