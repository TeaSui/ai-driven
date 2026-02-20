package com.aidriven.spi.tenant;

import com.aidriven.spi.ModuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultModuleContextTest {

    private ModuleContext context;

    @BeforeEach
    void setUp() {
        TenantConfig config = TenantConfig.builder("acme")
                .config("api.url", "https://api.example.com")
                .config("max.retries", "3")
                .config("debug.enabled", "true")
                .secret("api.key", "arn:aws:sm:us-east-1:123:secret:api-key")
                .build();

        context = new DefaultModuleContext(
                config,
                arn -> "resolved-secret-for-" + arn,
                arn -> Map.of("key", "value"));
    }

    @Test
    void should_return_tenant_id() {
        assertEquals("acme", context.tenantId());
    }

    @Test
    void should_get_required_config() {
        assertEquals("https://api.example.com", context.getRequiredConfig("api.url"));
    }

    @Test
    void should_throw_for_missing_required_config() {
        assertThrows(IllegalArgumentException.class, () ->
                context.getRequiredConfig("nonexistent"));
    }

    @Test
    void should_get_optional_config() {
        assertTrue(context.getConfig("api.url").isPresent());
        assertTrue(context.getConfig("nonexistent").isEmpty());
    }

    @Test
    void should_get_config_with_default() {
        assertEquals("https://api.example.com", context.getConfig("api.url", "default"));
        assertEquals("default", context.getConfig("missing", "default"));
    }

    @Test
    void should_get_int_config() {
        assertEquals(3, context.getIntConfig("max.retries", 1));
        assertEquals(5, context.getIntConfig("missing", 5));
    }

    @Test
    void should_get_bool_config() {
        assertTrue(context.getBoolConfig("debug.enabled", false));
        assertFalse(context.getBoolConfig("missing", false));
    }

    @Test
    void should_get_all_config() {
        Map<String, String> all = context.getAllConfig();
        assertEquals(3, all.size());
        assertTrue(all.containsKey("api.url"));
    }

    @Test
    void should_resolve_secret_via_arn_mapping() {
        String secret = context.resolveSecret("api.key");
        assertEquals("resolved-secret-for-arn:aws:sm:us-east-1:123:secret:api-key", secret);
    }

    @Test
    void should_resolve_secret_directly_when_no_mapping() {
        String secret = context.resolveSecret("direct-key");
        assertEquals("resolved-secret-for-direct-key", secret);
    }

    @Test
    void should_resolve_json_secret() {
        Map<String, Object> json = context.resolveSecretJson("api.key");
        assertNotNull(json);
        assertEquals("value", json.get("key"));
    }

    @Test
    void should_work_with_simplified_constructor() {
        TenantConfig config = TenantConfig.builder("simple")
                .config("key", "value")
                .build();
        ModuleContext simpleContext = new DefaultModuleContext(config);

        assertEquals("simple", simpleContext.tenantId());
        assertEquals("value", simpleContext.getRequiredConfig("key"));
        assertNull(simpleContext.resolveSecret("any")); // no resolver
    }
}
