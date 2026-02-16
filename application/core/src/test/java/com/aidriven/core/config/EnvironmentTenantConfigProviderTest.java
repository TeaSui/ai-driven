package com.aidriven.core.config;

import com.aidriven.spi.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentTenantConfigProviderTest {

    @Test
    void should_return_default_tenant() {
        EnvironmentTenantConfigProvider provider = new EnvironmentTenantConfigProvider();

        TenantContext ctx = provider.getDefault();

        assertNotNull(ctx);
        assertEquals("default", ctx.getTenantId());
        assertEquals("Default Tenant", ctx.getTenantName());
    }

    @Test
    void should_resolve_default_tenant_by_id() {
        EnvironmentTenantConfigProvider provider = new EnvironmentTenantConfigProvider();

        Optional<TenantContext> ctx = provider.resolve("default");

        assertTrue(ctx.isPresent());
        assertEquals("default", ctx.get().getTenantId());
    }

    @Test
    void should_return_empty_for_unknown_tenant() {
        EnvironmentTenantConfigProvider provider = new EnvironmentTenantConfigProvider();

        Optional<TenantContext> ctx = provider.resolve("unknown-tenant");

        assertTrue(ctx.isEmpty());
    }

    @Test
    void should_return_same_default_instance() {
        EnvironmentTenantConfigProvider provider = new EnvironmentTenantConfigProvider();

        TenantContext first = provider.getDefault();
        TenantContext second = provider.getDefault();

        assertSame(first, second);
    }
}
