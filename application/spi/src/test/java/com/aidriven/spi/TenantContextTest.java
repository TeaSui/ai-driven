package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @Test
    void should_create_tenant_context_with_all_fields() {
        TenantContext ctx = TenantContext.of("acme", "Acme Corp",
                Map.of("sourcecontrolprovider", "github"));

        assertEquals("acme", ctx.tenantId());
        assertEquals("Acme Corp", ctx.tenantName());
        assertEquals("github", ctx.getConfig("sourcecontrolprovider"));
    }

    @Test
    void should_return_default_for_missing_config() {
        TenantContext ctx = TenantContext.of("t1", "Tenant 1", Map.of());

        assertNull(ctx.getConfig("missing"));
        assertEquals("fallback", ctx.getConfig("missing", "fallback"));
    }

    @Test
    void should_create_default_tenant() {
        TenantContext ctx = TenantContext.defaultTenant();

        assertEquals("default", ctx.tenantId());
        assertEquals("Default Tenant", ctx.tenantName());
        assertTrue(ctx.configuration().isEmpty());
    }

    @Test
    void should_throw_on_null_tenant_id() {
        assertThrows(NullPointerException.class,
                () -> new TenantContext(null, "name", Map.of()));
    }

    @Test
    void should_throw_on_null_tenant_name() {
        assertThrows(NullPointerException.class,
                () -> new TenantContext("id", null, Map.of()));
    }

    @Test
    void should_default_null_config_to_empty_map() {
        TenantContext ctx = new TenantContext("id", "name", null);
        assertNotNull(ctx.configuration());
        assertTrue(ctx.configuration().isEmpty());
    }
}
