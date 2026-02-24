package com.aidriven.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantRegistryTest {

    private TenantRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();
    }

    @Test
    void should_register_and_retrieve_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme-corp")
                .tenantName("ACME Corporation")
                .active(true)
                .build();

        registry.register(config);

        assertTrue(registry.getTenant("acme-corp").isPresent());
        assertEquals("ACME Corporation", registry.getTenant("acme-corp").get().getTenantName());
    }

    @Test
    void should_return_empty_for_unknown_tenant() {
        assertTrue(registry.getTenant("unknown").isEmpty());
    }

    @Test
    void should_throw_for_required_unknown_tenant() {
        assertThrows(TenantRegistry.TenantNotFoundException.class,
                () -> registry.getRequiredTenant("unknown"));
    }

    @Test
    void should_throw_for_null_tenant_id_on_register() {
        TenantConfig config = TenantConfig.builder().tenantId(null).build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(config));
    }

    @Test
    void should_throw_for_blank_tenant_id_on_register() {
        TenantConfig config = TenantConfig.builder().tenantId("  ").build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(config));
    }

    @Test
    void should_throw_for_null_config_on_register() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void should_check_active_status() {
        TenantConfig active = TenantConfig.builder().tenantId("active").active(true).build();
        TenantConfig inactive = TenantConfig.builder().tenantId("inactive").active(false).build();

        registry.register(active);
        registry.register(inactive);

        assertTrue(registry.isActive("active"));
        assertFalse(registry.isActive("inactive"));
        assertFalse(registry.isActive("unknown"));
    }

    @Test
    void should_deregister_tenant() {
        TenantConfig config = TenantConfig.builder().tenantId("temp").active(true).build();
        registry.register(config);

        assertTrue(registry.deregister("temp"));
        assertTrue(registry.getTenant("temp").isEmpty());
    }

    @Test
    void should_return_false_when_deregistering_unknown_tenant() {
        assertFalse(registry.deregister("unknown"));
    }

    @Test
    void should_return_all_tenants() {
        registry.register(TenantConfig.builder().tenantId("t1").active(true).build());
        registry.register(TenantConfig.builder().tenantId("t2").active(true).build());

        assertEquals(2, registry.getAllTenants().size());
        assertEquals(2, registry.size());
    }

    @Test
    void should_create_default_tenant() {
        TenantConfig config = TenantConfig.defaultTenant("default");

        assertEquals("default", config.getTenantId());
        assertTrue(config.isActive());
        assertTrue(config.isGuardrailsEnabled());
        assertEquals(200_000, config.getTokenBudget());
        assertEquals(10, config.getMaxTurns());
    }

    @Test
    void should_check_plugin_enabled() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("t1")
                .active(true)
                .enabledPlugins(Set.of("monitoring", "messaging"))
                .build();

        assertTrue(config.isPluginEnabled("monitoring"));
        assertTrue(config.isPluginEnabled("messaging"));
        assertFalse(config.isPluginEnabled("data"));
    }

    @Test
    void should_return_effective_token_budget() {
        TenantConfig withBudget = TenantConfig.builder().tenantId("t1").tokenBudget(500_000).build();
        TenantConfig noBudget = TenantConfig.builder().tenantId("t2").tokenBudget(0).build();

        assertEquals(500_000, withBudget.getEffectiveTokenBudget(200_000));
        assertEquals(200_000, noBudget.getEffectiveTokenBudget(200_000));
    }
}
