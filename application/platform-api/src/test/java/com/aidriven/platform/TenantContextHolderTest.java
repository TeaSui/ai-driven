package com.aidriven.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextHolderTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void should_return_default_tenant_when_not_set() {
        TenantContext ctx = TenantContextHolder.get();

        assertNotNull(ctx);
        assertEquals("default", ctx.tenantId());
    }

    @Test
    void should_return_set_tenant() {
        TenantContext tenant = TenantContext.of("t1", "Acme", Map.of());
        TenantContextHolder.set(tenant);

        TenantContext result = TenantContextHolder.get();

        assertEquals("t1", result.tenantId());
        assertEquals("Acme", result.tenantName());
    }

    @Test
    void should_clear_tenant_context() {
        TenantContextHolder.set(TenantContext.of("t1", "Acme", Map.of()));
        TenantContextHolder.clear();

        assertEquals("default", TenantContextHolder.get().tenantId());
    }

    @Test
    void require_should_throw_when_not_set() {
        assertThrows(IllegalStateException.class, TenantContextHolder::require);
    }

    @Test
    void require_should_return_context_when_set() {
        TenantContextHolder.set(TenantContext.of("t1", "Acme", Map.of()));

        TenantContext ctx = TenantContextHolder.require();

        assertEquals("t1", ctx.tenantId());
    }
}
