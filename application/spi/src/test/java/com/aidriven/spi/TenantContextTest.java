package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @Test
    void should_create_context_with_builder() {
        TenantContext context = TenantContext.builder("tenant-1")
                .tenantName("Acme Corp")
                .config("jira.baseUrl", "https://acme.atlassian.net")
                .secretArn("jira", "arn:aws:secret:jira")
                .enableModule("jira-client")
                .featureFlag("agent-mode", true)
                .build();

        assertEquals("tenant-1", context.getTenantId());
        assertEquals("Acme Corp", context.getTenantName());
        assertEquals(Optional.of("https://acme.atlassian.net"), context.getConfig("jira.baseUrl"));
        assertEquals(Optional.of("arn:aws:secret:jira"), context.getSecretArn("jira"));
        assertTrue(context.isModuleEnabled("jira-client"));
        assertFalse(context.isModuleEnabled("github-client"));
        assertTrue(context.isFeatureEnabled("agent-mode"));
        assertFalse(context.isFeatureEnabled("nonexistent"));
    }

    @Test
    void should_throw_for_null_tenant_id() {
        assertThrows(NullPointerException.class, () ->
                TenantContext.builder(null).build());
    }

    @Test
    void should_return_empty_for_missing_config() {
        TenantContext context = TenantContext.builder("t1").build();

        assertTrue(context.getConfig("missing").isEmpty());
    }

    @Test
    void should_return_default_for_missing_config() {
        TenantContext context = TenantContext.builder("t1").build();

        assertEquals("fallback", context.getConfig("missing", "fallback"));
    }

    @Test
    void should_throw_for_missing_required_config() {
        TenantContext context = TenantContext.builder("t1").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                context.getRequiredConfig("missing.key"));

        assertTrue(ex.getMessage().contains("missing.key"));
        assertTrue(ex.getMessage().contains("t1"));
    }

    @Test
    void should_default_tenant_name_to_id() {
        TenantContext context = TenantContext.builder("my-tenant").build();

        assertEquals("my-tenant", context.getTenantName());
    }

    @Test
    void should_return_unmodifiable_config_map() {
        TenantContext context = TenantContext.builder("t1")
                .config("key", "value")
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                context.getAllConfig().put("new", "value"));
    }

    @Test
    void should_return_unmodifiable_enabled_modules() {
        TenantContext context = TenantContext.builder("t1")
                .enableModule("mod1")
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                context.getEnabledModules().add("mod2"));
    }
}
