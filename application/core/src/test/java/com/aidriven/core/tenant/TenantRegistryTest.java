package com.aidriven.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantRegistryTest {

    private TenantRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();
    }

    @Test
    void register_and_find_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme-corp")
                .tenantName("ACME Corporation")
                .defaultPlatform("GITHUB")
                .build();

        registry.register(config);

        Optional<TenantConfig> found = registry.find("acme-corp");
        assertTrue(found.isPresent());
        assertEquals("ACME Corporation", found.get().getTenantName());
    }

    @Test
    void find_returns_empty_for_unknown_tenant() {
        Optional<TenantConfig> found = registry.find("unknown");
        assertTrue(found.isEmpty());
    }

    @Test
    void find_returns_empty_for_null_id() {
        Optional<TenantConfig> found = registry.find(null);
        assertTrue(found.isEmpty());
    }

    @Test
    void getOrThrow_throws_for_unknown_tenant() {
        assertThrows(TenantRegistry.TenantNotFoundException.class,
                () -> registry.getOrThrow("nonexistent"));
    }

    @Test
    void register_throws_for_null_config() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void register_throws_for_blank_tenant_id() {
        TenantConfig config = TenantConfig.builder().tenantId("").build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(config));
    }

    @Test
    void deregister_removes_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("temp-tenant")
                .tenantName("Temp")
                .build();
        registry.register(config);
        assertTrue(registry.isRegistered("temp-tenant"));

        registry.deregister("temp-tenant");
        assertFalse(registry.isRegistered("temp-tenant"));
    }

    @Test
    void size_reflects_registered_tenants() {
        assertEquals(0, registry.size());

        registry.register(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        registry.register(TenantConfig.builder().tenantId("t2").tenantName("T2").build());

        assertEquals(2, registry.size());
    }

    @Test
    void getTenantIds_returns_all_ids() {
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        registry.register(TenantConfig.builder().tenantId("t2").tenantName("T2").build());

        assertTrue(registry.getTenantIds().containsAll(Set.of("t1", "t2")));
    }

    @Test
    void register_overwrites_existing_tenant() {
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("Old Name").build());
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("New Name").build());

        assertEquals("New Name", registry.getOrThrow("t1").getTenantName());
        assertEquals(1, registry.size());
    }

    @Test
    void feature_flag_check() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("feature-tenant")
                .tenantName("Feature Tenant")
                .featureFlags(Map.of("agent-mode", true, "dry-run-only", false))
                .build();
        registry.register(config);

        TenantConfig found = registry.getOrThrow("feature-tenant");
        assertTrue(found.isFeatureEnabled("agent-mode"));
        assertFalse(found.isFeatureEnabled("dry-run-only"));
        assertFalse(found.isFeatureEnabled("nonexistent-flag"));
    }

    @Test
    void plugin_enabled_check() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("plugin-tenant")
                .tenantName("Plugin Tenant")
                .enabledPlugins(Set.of("monitoring", "messaging"))
                .build();
        registry.register(config);

        TenantConfig found = registry.getOrThrow("plugin-tenant");
        assertTrue(found.isPluginEnabled("monitoring"));
        assertTrue(found.isPluginEnabled("messaging"));
        assertFalse(found.isPluginEnabled("data"));
    }
}
