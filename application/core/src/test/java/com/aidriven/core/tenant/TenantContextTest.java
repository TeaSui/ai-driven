package com.aidriven.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void set_and_get_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("ACME Corp")
                .active(true)
                .build();

        TenantContext.set(config);

        assertNotNull(TenantContext.get());
        assertEquals("acme", TenantContext.get().getTenantId());
    }

    @Test
    void getTenantId_returns_default_when_not_set() {
        assertEquals("default", TenantContext.getTenantId());
    }

    @Test
    void getTenantId_returns_tenant_id_when_set() {
        TenantContext.set(TenantConfig.builder().tenantId("my-tenant").active(true).build());
        assertEquals("my-tenant", TenantContext.getTenantId());
    }

    @Test
    void isSet_returns_false_when_not_set() {
        assertFalse(TenantContext.isSet());
    }

    @Test
    void isSet_returns_true_when_set() {
        TenantContext.set(TenantConfig.builder().tenantId("t1").active(true).build());
        assertTrue(TenantContext.isSet());
    }

    @Test
    void clear_removes_tenant() {
        TenantContext.set(TenantConfig.builder().tenantId("t1").active(true).build());
        TenantContext.clear();
        assertFalse(TenantContext.isSet());
        assertNull(TenantContext.get());
    }
}
