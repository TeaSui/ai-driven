package com.aidriven.core.tenant;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Thread-local tenant context for the current request.
 * Allows downstream components to access the current tenant
 * without passing it through every method call.
 *
 * <p>Usage:
 * <pre>{@code
 * TenantContext.set(tenantConfig);
 * try {
 *     // process request
 * } finally {
 *     TenantContext.clear();
 * }
 * }</pre>
 */
@Slf4j
public final class TenantContext {

    private static final ThreadLocal<TenantConfig> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * Sets the current tenant for this thread.
     *
     * @param tenant The tenant configuration
     */
    public static void set(TenantConfig tenant) {
        CURRENT_TENANT.set(tenant);
        if (tenant != null) {
            log.debug("Set tenant context: {}", tenant.getTenantId());
        }
    }

    /**
     * Returns the current tenant, if set.
     */
    public static Optional<TenantConfig> current() {
        return Optional.ofNullable(CURRENT_TENANT.get());
    }

    /**
     * Returns the current tenant ID, or "default" if not set.
     */
    public static String currentTenantId() {
        TenantConfig config = CURRENT_TENANT.get();
        return config != null ? config.getTenantId() : "default";
    }

    /**
     * Clears the current tenant context.
     * Must be called after request processing to prevent memory leaks.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Returns true if a tenant context is currently set.
     */
    public static boolean isSet() {
        return CURRENT_TENANT.get() != null;
    }
}
