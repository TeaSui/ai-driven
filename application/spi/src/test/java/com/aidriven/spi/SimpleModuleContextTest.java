package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SimpleModuleContextTest {

    @Test
    void should_return_config_value() {
        ModuleContext ctx = SimpleModuleContext.builder("t1")
                .config("baseUrl", "https://example.com")
                .build();

        assertEquals(Optional.of("https://example.com"), ctx.getConfig("baseUrl"));
    }

    @Test
    void should_return_empty_for_missing_config() {
        ModuleContext ctx = SimpleModuleContext.builder("t1").build();

        assertTrue(ctx.getConfig("missing").isEmpty());
    }

    @Test
    void should_return_default_for_missing_config() {
        ModuleContext ctx = SimpleModuleContext.builder("t1").build();

        assertEquals("fallback", ctx.getConfig("missing", "fallback"));
    }

    @Test
    void should_return_int_config() {
        ModuleContext ctx = SimpleModuleContext.builder("t1")
                .config("maxTokens", "32768")
                .build();

        assertEquals(32768, ctx.getIntConfig("maxTokens", 0));
    }

    @Test
    void should_return_default_for_non_numeric_int_config() {
        ModuleContext ctx = SimpleModuleContext.builder("t1")
                .config("maxTokens", "not-a-number")
                .build();

        assertEquals(100, ctx.getIntConfig("maxTokens", 100));
    }

    @Test
    void should_return_secret() {
        ModuleContext ctx = SimpleModuleContext.builder("t1")
                .secret("apiToken", "sk-test")
                .build();

        assertEquals("sk-test", ctx.getSecret("apiToken"));
    }

    @Test
    void should_throw_for_missing_secret() {
        ModuleContext ctx = SimpleModuleContext.builder("t1").build();

        assertThrows(IllegalStateException.class, () -> ctx.getSecret("missing"));
    }

    @Test
    void should_return_tenant_id() {
        ModuleContext ctx = SimpleModuleContext.builder("company-abc").build();

        assertEquals("company-abc", ctx.tenantId());
    }

    @Test
    void should_return_all_config() {
        ModuleContext ctx = SimpleModuleContext.builder("t1")
                .config("a", "1")
                .config("b", "2")
                .build();

        Map<String, String> all = ctx.getAllConfig();
        assertEquals(2, all.size());
        assertEquals("1", all.get("a"));
    }

    @Test
    void should_return_all_secrets() {
        ModuleContext ctx = SimpleModuleContext.builder("t1")
                .secret("key1", "val1")
                .secret("key2", "val2")
                .build();

        assertEquals(2, ctx.getSecrets().size());
    }

    @Test
    void should_build_with_bulk_configs() {
        ModuleContext ctx = SimpleModuleContext.builder("t1")
                .configs(Map.of("a", "1", "b", "2"))
                .secrets(Map.of("s1", "v1"))
                .build();

        assertEquals("1", ctx.getConfig("a", ""));
        assertEquals("v1", ctx.getSecret("s1"));
    }

    @Test
    void should_be_immutable() {
        SimpleModuleContext ctx = SimpleModuleContext.builder("t1")
                .config("key", "value")
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.getAllConfig().put("new", "val"));
    }
}
