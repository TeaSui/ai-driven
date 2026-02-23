package com.aidriven.core.tenant;

/**
 * Thread-local holder for the current tenant context.
 * Set at the beginning of each Lambda invocation and cleared at the end.
 *
 * <p>This enables tenant-aware services to access the current tenant
 * without passing it through every method call.</p>
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
     * Returns the current tenant ID, or "default" if not set.
     */
    public static String getTenantId() {
        TenantConfig config = CURRENT.get();
        return config != null ? config.getTenantId() : "default";
    }

    /**
     * Clears the current tenant context.
     * Must be called at the end of each Lambda invocation to prevent leaks.
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
