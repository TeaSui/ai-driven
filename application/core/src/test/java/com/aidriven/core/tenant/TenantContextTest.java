package com.aidriven.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void set_and_get_tenant_context() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("test-tenant")
                .tenantName("Test Tenant")
                .build();

        TenantContext.set(config);

        assertNotNull(TenantContext.get());
        assertEquals("test-tenant", TenantContext.getTenantId());
    }

    @Test
    void get_returns_null_when_not_set() {
        assertNull(TenantContext.get());
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void isSet_returns_false_when_not_set() {
        assertFalse(TenantContext.isSet());
    }

    @Test
    void isSet_returns_true_when_set() {
        TenantContext.set(TenantConfig.builder().tenantId("t").tenantName("T").build());
        assertTrue(TenantContext.isSet());
    }

    @Test
    void clear_removes_context() {
        TenantContext.set(TenantConfig.builder().tenantId("t").tenantName("T").build());
        TenantContext.clear();
        assertFalse(TenantContext.isSet());
        assertNull(TenantContext.get());
    }

    @Test
    void require_throws_when_not_set() {
        assertThrows(IllegalStateException.class, TenantContext::require);
    }

    @Test
    void require_returns_config_when_set() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("required-tenant")
                .tenantName("Required")
                .build();
        TenantContext.set(config);

        TenantConfig result = TenantContext.require();
        assertEquals("required-tenant", result.getTenantId());
    }
}
