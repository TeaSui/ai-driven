package com.aidriven.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextHolderTest {

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void set_and_get_current_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme Corp")
                .build();

        TenantContextHolder.setCurrentTenant(config);

        assertTrue(TenantContextHolder.hasTenant());
        assertTrue(TenantContextHolder.getCurrentTenant().isPresent());
        assertEquals("acme", TenantContextHolder.getCurrentTenant().get().getTenantId());
    }

    @Test
    void getCurrentTenantId_returns_default_when_not_set() {
        assertEquals("default", TenantContextHolder.getCurrentTenantId());
    }

    @Test
    void getCurrentTenantId_returns_tenant_id_when_set() {
        TenantContextHolder.setCurrentTenant(
                TenantConfig.builder().tenantId("startup").tenantName("Startup").build());
        assertEquals("startup", TenantContextHolder.getCurrentTenantId());
    }

    @Test
    void clear_removes_tenant_context() {
        TenantContextHolder.setCurrentTenant(
                TenantConfig.builder().tenantId("acme").tenantName("Acme").build());
        assertTrue(TenantContextHolder.hasTenant());

        TenantContextHolder.clear();

        assertFalse(TenantContextHolder.hasTenant());
        assertTrue(TenantContextHolder.getCurrentTenant().isEmpty());
    }

    @Test
    void hasTenant_returns_false_when_not_set() {
        assertFalse(TenantContextHolder.hasTenant());
    }

    @Test
    void getCurrentTenant_returns_empty_when_not_set() {
        assertTrue(TenantContextHolder.getCurrentTenant().isEmpty());
    }
}
