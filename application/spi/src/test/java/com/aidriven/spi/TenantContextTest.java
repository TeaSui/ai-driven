package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @Test
    void should_build_tenant_context_with_all_fields() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("tenant-001")
                .tenantName("Acme Corp")
                .config("jira.baseUrl", "https://acme.atlassian.net")
                .config("jira.email", "bot@acme.com")
                .build();

        assertEquals("tenant-001", ctx.tenantId());
        assertEquals("Acme Corp", ctx.tenantName());
        assertEquals("https://acme.atlassian.net", ctx.requireConfig("jira.baseUrl"));
    }

    @Test
    void should_throw_for_null_tenant_id() {
        assertThrows(NullPointerException.class, () ->
                TenantContext.builder().tenantName("Test").config(Map.of()).build());
    }

    @Test
    void should_throw_for_missing_required_config() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .build();

        assertThrows(IllegalStateException.class, () -> ctx.requireConfig("missing.key"));
    }

    @Test
    void should_return_empty_optional_for_missing_config() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .build();

        assertTrue(ctx.getConfig("missing").isEmpty());
    }

    @Test
    void should_return_default_for_missing_config() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .build();

        assertEquals("fallback", ctx.getConfig("missing", "fallback"));
    }

    @Test
    void should_parse_int_config() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .config("maxTokens", "50000")
                .build();

        assertEquals(50000, ctx.getIntConfig("maxTokens", 10000));
    }

    @Test
    void should_return_default_for_invalid_int_config() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .config("maxTokens", "not-a-number")
                .build();

        assertEquals(10000, ctx.getIntConfig("maxTokens", 10000));
    }

    @Test
    void should_parse_bool_config() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .config("dryRun", "true")
                .build();

        assertTrue(ctx.getBoolConfig("dryRun", false));
    }

    @Test
    void should_return_default_for_missing_bool_config() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .build();

        assertFalse(ctx.getBoolConfig("dryRun", false));
    }

    @Test
    void should_have_immutable_config() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .config("key", "value")
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                ctx.config().put("new", "value"));
    }

    @Test
    void should_implement_equals_by_tenant_id() {
        TenantContext a = TenantContext.builder().tenantId("t1").tenantName("A").build();
        TenantContext b = TenantContext.builder().tenantId("t1").tenantName("B").build();
        TenantContext c = TenantContext.builder().tenantId("t2").tenantName("A").build();

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void should_build_with_config_map() {
        Map<String, String> configMap = Map.of("key1", "val1", "key2", "val2");
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .config(configMap)
                .build();

        assertEquals("val1", ctx.requireConfig("key1"));
        assertEquals("val2", ctx.requireConfig("key2"));
    }

    @Test
    void should_treat_blank_config_as_missing() {
        TenantContext ctx = TenantContext.builder()
                .tenantId("t1")
                .tenantName("Test")
                .config("blank", "  ")
                .build();

        assertTrue(ctx.getConfig("blank").isEmpty());
        assertThrows(IllegalStateException.class, () -> ctx.requireConfig("blank"));
    }
}
