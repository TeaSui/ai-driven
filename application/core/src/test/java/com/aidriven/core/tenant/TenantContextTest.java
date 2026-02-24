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
    void set_and_current_returns_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme Corp")
                .build();

        TenantContext.set(config);

        assertTrue(TenantContext.isSet());
        assertTrue(TenantContext.current().isPresent());
        assertEquals("acme", TenantContext.current().get().getTenantId());
    }

    @Test
    void clear_removes_tenant() {
        TenantContext.set(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        TenantContext.clear();

        assertFalse(TenantContext.isSet());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void currentTenantId_returns_default_when_not_set() {
        assertEquals("default", TenantContext.currentTenantId());
    }

    @Test
    void currentTenantId_returns_tenant_id_when_set() {
        TenantContext.set(TenantConfig.builder().tenantId("startup").tenantName("Startup").build());
        assertEquals("startup", TenantContext.currentTenantId());
    }

    @Test
    void isSet_returns_false_initially() {
        assertFalse(TenantContext.isSet());
    }

    @Test
    void set_null_clears_context() {
        TenantContext.set(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        TenantContext.set(null);
        assertFalse(TenantContext.isSet());
    }
}
