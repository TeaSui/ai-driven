package com.aidriven.spi;

import java.util.Optional;

/**
 * Thread-local holder for the current tenant context.
 *
 * <p>In a multi-tenant environment, each request is associated with a tenant.
 * This holder makes the tenant context available throughout the call stack
 * without passing it as a parameter.</p>
 *
 * <p>Usage pattern (e.g., in Lambda handler):</p>
 * <pre>{@code
 * TenantContextHolder.set(tenantContext);
 * try {
 *     // All downstream code can access TenantContextHolder.get()
 *     processRequest(...);
 * } finally {
 *     TenantContextHolder.clear();
 * }
 * }</pre>
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    /**
     * Sets the tenant context for the current thread.
     */
    public static void set(TenantContext context) {
        HOLDER.set(context);
    }

    /**
     * Gets the tenant context for the current thread.
     *
     * @return The current tenant context, or empty if not set
     */
    public static Optional<TenantContext> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    /**
     * Gets the tenant context, throwing if not set.
     *
     * @return The current tenant context
     * @throws IllegalStateException if no tenant context is set
     */
    public static TenantContext require() {
        TenantContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("No tenant context set for current thread");
        }
        return ctx;
    }

    /**
     * Clears the tenant context for the current thread.
     * Must be called in a finally block to prevent memory leaks.
     */
    public static void clear() {
        HOLDER.remove();
    }
}
