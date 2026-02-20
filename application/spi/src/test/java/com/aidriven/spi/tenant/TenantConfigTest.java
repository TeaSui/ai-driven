package com.aidriven.spi.tenant;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigTest {

    @Test
    void should_build_with_all_fields() {
        TenantConfig config = TenantConfig.builder("acme")
                .bindModule("source-control", "github")
                .bindModule("issue-tracker", "jira")
                .config("github.owner", "acme-corp")
                .config("github.repo", "backend")
                .secret("github.token", "arn:aws:sm:us-east-1:123:secret:gh")
                .enabled(true)
                .build();

        assertEquals("acme", config.tenantId());
        assertEquals("github", config.getModuleBinding("source-control").orElse(null));
        assertEquals("jira", config.getModuleBinding("issue-tracker").orElse(null));
        assertEquals("acme-corp", config.config().get("github.owner"));
        assertTrue(config.enabled());
    }

    @Test
    void should_return_empty_for_unknown_binding() {
        TenantConfig config = TenantConfig.builder("test").build();
        assertTrue(config.getModuleBinding("nonexistent").isEmpty());
    }

    @Test
    void should_throw_on_null_tenant_id() {
        assertThrows(NullPointerException.class, () ->
                new TenantConfig(null, Map.of(), Map.of(), Map.of(), true));
    }

    @Test
    void should_handle_null_maps_gracefully() {
        TenantConfig config = new TenantConfig("test", null, null, null, true);
        assertNotNull(config.moduleBindings());
        assertNotNull(config.config());
        assertNotNull(config.secrets());
        assertTrue(config.moduleBindings().isEmpty());
    }

    @Test
    void should_be_immutable() {
        TenantConfig config = TenantConfig.builder("test")
                .config("key", "value")
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                config.config().put("new", "value"));
    }

    @Test
    void should_support_bulk_configs() {
        TenantConfig config = TenantConfig.builder("test")
                .configs(Map.of("a", "1", "b", "2"))
                .build();

        assertEquals("1", config.config().get("a"));
        assertEquals("2", config.config().get("b"));
    }
}
