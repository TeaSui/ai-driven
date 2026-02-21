package com.aidriven.core.tenant;

/**
 * Thread-local holder for the current tenant context.
 *
 * <p>Set at the beginning of each Lambda invocation (from webhook headers
 * or payload) and cleared at the end. Allows downstream services to
 * access the current tenant without passing it through every method call.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * // In webhook handler:
 * TenantContext.set("acme-corp");
 * try {
 *     // ... process request ...
 * } finally {
 *     TenantContext.clear();
 * }
 *
 * // In any service:
 * String tenantId = TenantContext.get(); // "acme-corp"
 * }</pre>
 */
public final class TenantContext {

    /** Header name for tenant identification in API Gateway requests. */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /** Query parameter name for tenant identification. */
    public static final String TENANT_QUERY_PARAM = "tenantId";

    /** Default tenant ID when no tenant is specified (single-tenant mode). */
    public static final String DEFAULT_TENANT = "default";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
        // Utility class
    }

    /**
     * Sets the current tenant ID for this thread.
     *
     * @param tenantId The tenant identifier
     */
    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId != null ? tenantId : DEFAULT_TENANT);
    }

    /**
     * Gets the current tenant ID for this thread.
     *
     * @return The tenant ID, or {@link #DEFAULT_TENANT} if not set
     */
    public static String get() {
        String tenantId = CURRENT_TENANT.get();
        return tenantId != null ? tenantId : DEFAULT_TENANT;
    }

    /**
     * Clears the current tenant context for this thread.
     * Should be called in a finally block after request processing.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Returns true if a tenant context has been explicitly set.
     */
    public static boolean isSet() {
        return CURRENT_TENANT.get() != null;
    }
}
