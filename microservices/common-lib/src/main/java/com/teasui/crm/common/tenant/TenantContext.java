package com.teasui.crm.common.tenant;

/**
 * Thread-local tenant context for multi-tenant support.
 * Each request carries tenant information for data isolation.
 */
public class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void setUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static String getUserId() {
        return CURRENT_USER_ID.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_USER_ID.remove();
    }

    public static boolean hasTenant() {
        return CURRENT_TENANT.get() != null;
    }
}
