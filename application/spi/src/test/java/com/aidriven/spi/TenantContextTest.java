package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @Test
    void should_build_tenant_context_with_modules() {
        TenantContext ctx = TenantContext.builder("tenant-1")
                .tenantName("Acme Corp")
                .enableModules("jira", "github", "claude")
                .moduleConfig("jira", Map.of("url", "https://acme.atlassian.net"))
                .moduleConfig("github", Map.of("org", "acme-corp"))
                .build();

        assertEquals("tenant-1", ctx.getTenantId());
        assertEquals("Acme Corp", ctx.getTenantName());
        assertEquals(Set.of("jira", "github", "claude"), ctx.getEnabledModules());
        assertTrue(ctx.isModuleEnabled("jira"));
        assertFalse(ctx.isModuleEnabled("bitbucket"));
    }

    @Test
    void should_return_module_config() {
        TenantContext ctx = TenantContext.builder("t1")
                .moduleConfig("jira", Map.of("url", "https://test.atlassian.net", "token", "abc"))
                .build();

        Map<String, String> config = ctx.getModuleConfig("jira");

        assertEquals("https://test.atlassian.net", config.get("url"));
        assertEquals("abc", config.get("token"));
    }

    @Test
    void should_return_empty_config_for_unknown_module() {
        TenantContext ctx = TenantContext.builder("t1")
                .enableModule("jira")
                .build();

        Map<String, String> config = ctx.getModuleConfig("unknown");

        assertNotNull(config);
        assertTrue(config.isEmpty());
    }

    @Test
    void should_use_tenant_id_as_name_when_name_not_set() {
        TenantContext ctx = TenantContext.builder("tenant-42").build();

        assertEquals("tenant-42", ctx.getTenantName());
    }

    @Test
    void should_require_tenant_id() {
        assertThrows(NullPointerException.class,
                () -> TenantContext.builder(null).build());
    }

    @Test
    void should_have_immutable_enabled_modules() {
        TenantContext ctx = TenantContext.builder("t1")
                .enableModule("jira")
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.getEnabledModules().add("hack"));
    }

    @Test
    void should_have_immutable_module_configs() {
        TenantContext ctx = TenantContext.builder("t1")
                .moduleConfig("jira", Map.of("key", "val"))
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.getAllModuleConfigs().put("hack", Map.of()));
    }

    @Test
    void should_auto_enable_module_when_config_provided() {
        TenantContext ctx = TenantContext.builder("t1")
                .moduleConfig("github", Map.of("token", "ghp_xxx"))
                .build();

        assertTrue(ctx.isModuleEnabled("github"));
    }

    @Test
    void should_format_toString() {
        TenantContext ctx = TenantContext.builder("t1")
                .enableModules("jira", "claude")
                .build();

        String str = ctx.toString();
        assertTrue(str.contains("t1"));
        assertTrue(str.contains("jira"));
        assertTrue(str.contains("claude"));
    }
}
