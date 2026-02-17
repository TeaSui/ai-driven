package com.aidriven.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @Test
    void should_create_tenant_context_with_config() {
        TenantContext ctx = TenantContext.of("tenant-1", "Acme Corp",
                Map.of("platform", "GITHUB", "repo", "acme/backend"));

        assertEquals("tenant-1", ctx.tenantId());
        assertEquals("Acme Corp", ctx.tenantName());
        assertEquals("GITHUB", ctx.getConfigValue("platform", "BITBUCKET"));
        assertEquals("acme/backend", ctx.getConfigValue("repo", ""));
    }

    @Test
    void should_return_default_value_for_missing_key() {
        TenantContext ctx = TenantContext.of("t1", "Test", Map.of());

        assertEquals("BITBUCKET", ctx.getConfigValue("platform", "BITBUCKET"));
    }

    @Test
    void should_throw_for_required_missing_key() {
        TenantContext ctx = TenantContext.of("t1", "Test", Map.of());

        assertThrows(IllegalStateException.class,
                () -> ctx.getRequiredConfigValue("missing_key"));
    }

    @Test
    void should_throw_for_required_blank_key() {
        TenantContext ctx = TenantContext.of("t1", "Test", Map.of("key", "  "));

        assertThrows(IllegalStateException.class,
                () -> ctx.getRequiredConfigValue("key"));
    }

    @Test
    void should_return_required_value_when_present() {
        TenantContext ctx = TenantContext.of("t1", "Test", Map.of("key", "value"));

        assertEquals("value", ctx.getRequiredConfigValue("key"));
    }

    @Test
    void should_create_default_tenant() {
        TenantContext ctx = TenantContext.defaultTenant();

        assertEquals("default", ctx.tenantId());
        assertEquals("Default Tenant", ctx.tenantName());
        assertTrue(ctx.config().isEmpty());
    }

    @Test
    void should_throw_for_null_tenant_id() {
        assertThrows(NullPointerException.class,
                () -> TenantContext.of(null, "name", Map.of()));
    }

    @Test
    void should_throw_for_null_tenant_name() {
        assertThrows(NullPointerException.class,
                () -> TenantContext.of("id", null, Map.of()));
    }

    @Test
    void should_handle_null_config_as_empty_map() {
        TenantContext ctx = new TenantContext("id", "name", null);

        assertNotNull(ctx.config());
        assertTrue(ctx.config().isEmpty());
    }

    @Test
    void should_make_config_unmodifiable() {
        Map<String, String> mutableConfig = new java.util.HashMap<>();
        mutableConfig.put("key", "value");

        TenantContext ctx = TenantContext.of("id", "name", mutableConfig);

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.config().put("new", "value"));
    }
}
