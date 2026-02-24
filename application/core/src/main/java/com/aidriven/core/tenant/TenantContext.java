package com.aidriven.core.tenant;

/**
 * Thread-local holder for the current tenant context.
 *
 * <p>Set at the beginning of each Lambda invocation and cleared at the end.
 * Allows downstream services to access the current tenant without
 * passing it through every method call.
 *
 * <p>Usage:
 * <pre>{@code
 * TenantContext.set(tenantConfig);
 * try {
 *     // ... process request ...
 * } finally {
 *     TenantContext.clear();
 * }
 * }</pre>
 */
public final class TenantContext {

    private static final ThreadLocal<TenantConfig> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * Sets the current tenant for this thread.
     *
     * @param config The tenant configuration
     */
    public static void set(TenantConfig config) {
        CURRENT.set(config);
    }

    /**
     * Returns the current tenant configuration.
     *
     * @return The current tenant, or null if not set
     */
    public static TenantConfig get() {
        return CURRENT.get();
    }

    /**
     * Returns the current tenant ID, or null if not set.
     */
    public static String getTenantId() {
        TenantConfig config = CURRENT.get();
        return config != null ? config.getTenantId() : null;
    }

    /**
     * Returns the current tenant configuration, throwing if not set.
     *
     * @throws IllegalStateException if no tenant context is set
     */
    public static TenantConfig require() {
        TenantConfig config = CURRENT.get();
        if (config == null) {
            throw new IllegalStateException(
                    "No tenant context set. Ensure TenantContext.set() is called before processing.");
        }
        return config;
    }

    /**
     * Clears the current tenant context.
     * Must be called in a finally block to prevent thread-local leaks.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Returns true if a tenant context is currently set.
     */
    public static boolean isSet() {
        return CURRENT.get() != null;
    }
}
