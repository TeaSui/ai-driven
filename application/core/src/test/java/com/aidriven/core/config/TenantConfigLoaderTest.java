package com.aidriven.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigLoaderTest {

    private TenantConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new TenantConfigLoader(AppConfig.getInstance());
    }

    @Test
    void should_return_default_config_for_null_tenant() {
        TenantAwareAppConfig config = loader.getConfig(null);
        assertNotNull(config);
        assertEquals("default", config.getTenantId());
    }

    @Test
    void should_return_default_config_for_blank_tenant() {
        TenantAwareAppConfig config = loader.getConfig("  ");
        assertNotNull(config);
        assertEquals("default", config.getTenantId());
    }

    @Test
    void should_return_default_config_via_convenience_method() {
        TenantAwareAppConfig config = loader.getDefaultConfig();
        assertNotNull(config);
        assertEquals("default", config.getTenantId());
    }

    @Test
    void should_cache_config() {
        TenantAwareAppConfig first = loader.getConfig("tenant-a");
        TenantAwareAppConfig second = loader.getConfig("tenant-a");
        assertSame(first, second);
    }

    @Test
    void should_invalidate_cache() {
        TenantAwareAppConfig first = loader.getConfig("tenant-a");
        loader.invalidate("tenant-a");
        TenantAwareAppConfig second = loader.getConfig("tenant-a");
        assertNotSame(first, second);
    }

    @Test
    void should_invalidate_all_cache() {
        loader.getConfig("a");
        loader.getConfig("b");
        loader.invalidateAll();

        // After invalidation, new instances are created
        TenantAwareAppConfig a = loader.getConfig("a");
        assertNotNull(a);
    }

    @Test
    void should_return_different_configs_for_different_tenants() {
        TenantAwareAppConfig a = loader.getConfig("tenant-a");
        TenantAwareAppConfig b = loader.getConfig("tenant-b");
        assertNotSame(a, b);
        assertEquals("tenant-a", a.getTenantId());
        assertEquals("tenant-b", b.getTenantId());
    }
}
