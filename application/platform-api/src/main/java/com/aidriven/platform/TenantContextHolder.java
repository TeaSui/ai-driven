package com.aidriven.platform;

/**
 * Thread-local holder for the current tenant context.
 * Set at the entry point (webhook handler, SQS consumer) and
 * available throughout the request processing chain.
 *
 * <p>Always call {@link #clear()} in a finally block to prevent
 * context leaking between Lambda invocations that reuse the same thread.</p>
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
     * @return The current tenant context, or the default tenant if none is set
     */
    public static TenantContext get() {
        TenantContext ctx = HOLDER.get();
        return ctx != null ? ctx : TenantContext.defaultTenant();
    }

    /**
     * Gets the tenant context, throwing if none is set.
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
     * Must be called in a finally block.
     */
    public static void clear() {
        HOLDER.remove();
    }
}
