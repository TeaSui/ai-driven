package com.aidriven.spi;

/**
 * Marker interface for services that can be initialized with tenant-specific configuration.
 * Modules implementing this interface receive a {@link TenantContext} during setup,
 * allowing them to configure credentials, endpoints, and behavior per tenant.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>Module is discovered via {@link ServiceDescriptor}</li>
 *   <li>{@link #initialize(TenantContext)} is called with tenant config</li>
 *   <li>Module is ready for use</li>
 *   <li>{@link #destroy()} is called during shutdown/cleanup</li>
 * </ol>
 */
public interface TenantAware {

    /**
     * Initializes this service with tenant-specific configuration.
     * Called once per tenant context. Implementations should validate
     * required configuration and establish connections.
     *
     * @param context The tenant context with configuration
     * @throws IllegalStateException if required configuration is missing
     * @throws RuntimeException if initialization fails
     */
    void initialize(TenantContext context);

    /**
     * Returns true if this service has been initialized and is ready for use.
     */
    boolean isInitialized();

    /**
     * Releases resources held by this service.
     * Called during shutdown or when switching tenant contexts.
     */
    default void destroy() {
        // Default no-op for stateless services
    }
}
