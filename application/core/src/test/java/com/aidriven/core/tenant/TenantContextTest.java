package com.aidriven.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void should_store_and_retrieve_current_context() {
        TenantContext ctx = new TenantContext(
                "tenant-1", "Acme Corp", "production",
                Map.of("jira", "arn:jira"), Map.of("key", "value"),
                Map.of("jira", true));

        TenantContext.setCurrent(ctx);

        Optional<TenantContext> current = TenantContext.getCurrent();
        assertTrue(current.isPresent());
        assertEquals("tenant-1", current.get().tenantId());
        assertEquals("Acme Corp", current.get().tenantName());
    }

    @Test
    void should_return_empty_when_no_context_set() {
        assertTrue(TenantContext.getCurrent().isEmpty());
    }

    @Test
    void should_clear_context() {
        TenantContext.setCurrent(TenantContext.defaultContext());
        assertTrue(TenantContext.getCurrent().isPresent());

        TenantContext.clear();
        assertTrue(TenantContext.getCurrent().isEmpty());
    }

    @Test
    void should_get_secret_arn_by_name() {
        TenantContext ctx = new TenantContext(
                "t1", "Test", "dev",
                Map.of("jira", "arn:jira:123", "claude", "arn:claude:456"),
                Map.of(), Map.of());

        assertEquals("arn:jira:123", ctx.getSecretArn("jira").orElse(""));
        assertEquals("arn:claude:456", ctx.getSecretArn("claude").orElse(""));
        assertTrue(ctx.getSecretArn("nonexistent").isEmpty());
    }

    @Test
    void should_get_secret_arn_empty_when_null_map() {
        TenantContext ctx = new TenantContext(
                "t1", "Test", "dev", null, null, null);

        assertTrue(ctx.getSecretArn("jira").isEmpty());
    }

    @Test
    void should_get_config_value() {
        TenantContext ctx = new TenantContext(
                "t1", "Test", "dev",
                Map.of(), Map.of("defaultPlatform", "GITHUB"), Map.of());

        assertEquals("GITHUB", ctx.getConfig("defaultPlatform").orElse(""));
        assertEquals("BITBUCKET", ctx.getConfig("missing", "BITBUCKET"));
    }

    @Test
    void should_check_module_enabled() {
        TenantContext ctx = new TenantContext(
                "t1", "Test", "dev",
                Map.of(), Map.of(),
                Map.of("jira", true, "github", false));

        assertTrue(ctx.isModuleEnabled("jira"));
        assertFalse(ctx.isModuleEnabled("github"));
        assertFalse(ctx.isModuleEnabled("nonexistent"));
    }

    @Test
    void should_check_module_enabled_with_null_map() {
        TenantContext ctx = new TenantContext(
                "t1", "Test", "dev", Map.of(), Map.of(), null);

        assertFalse(ctx.isModuleEnabled("jira"));
    }

    @Test
    void should_create_default_context() {
        TenantContext ctx = TenantContext.defaultContext();

        assertEquals("default", ctx.tenantId());
        assertEquals("Default Tenant", ctx.tenantName());
        assertNotNull(ctx.secretArns());
        assertNotNull(ctx.configuration());
        assertNotNull(ctx.enabledModules());
    }

    @Test
    void should_isolate_contexts_between_threads() throws Exception {
        TenantContext ctx1 = new TenantContext(
                "tenant-1", "Tenant 1", "prod",
                Map.of(), Map.of(), Map.of());
        TenantContext ctx2 = new TenantContext(
                "tenant-2", "Tenant 2", "prod",
                Map.of(), Map.of(), Map.of());

        TenantContext.setCurrent(ctx1);

        Thread otherThread = new Thread(() -> {
            TenantContext.setCurrent(ctx2);
            assertEquals("tenant-2", TenantContext.getCurrent().get().tenantId());
            TenantContext.clear();
        });
        otherThread.start();
        otherThread.join();

        // Main thread still has ctx1
        assertEquals("tenant-1", TenantContext.getCurrent().get().tenantId());
    }
}