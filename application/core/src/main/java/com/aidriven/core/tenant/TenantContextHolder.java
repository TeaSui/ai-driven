package com.aidriven.core.tenant;

import java.util.Optional;

/**
 * Thread-local holder for the current tenant context.
 *
 * <p>Allows tenant-aware code to access the current tenant without
 * passing it through every method call. Follows the same pattern as
 * Spring's SecurityContextHolder.</p>
 *
 * <p>Important: Always call {@link #clear()} after processing to prevent
 * memory leaks in thread-pool environments (e.g., Lambda warm invocations).</p>
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantConfig> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {
        // Utility class
    }

    /**
     * Sets the current tenant context.
     *
     * @param tenantConfig The tenant configuration for the current request
     */
    public static void setCurrentTenant(TenantConfig tenantConfig) {
        CONTEXT.set(tenantConfig);
    }

    /**
     * Returns the current tenant configuration.
     *
     * @return Optional containing the current tenant, or empty if not set
     */
    public static Optional<TenantConfig> getCurrentTenant() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /**
     * Returns the current tenant ID, or "default" if not set.
     */
    public static String getCurrentTenantId() {
        return getCurrentTenant()
                .map(TenantConfig::getTenantId)
                .orElse("default");
    }

    /**
     * Clears the current tenant context.
     * Must be called after each Lambda invocation to prevent context leakage.
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Checks if a tenant context is currently set.
     */
    public static boolean hasTenant() {
        return CONTEXT.get() != null;
    }
}
