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
    void register_and_retrieve_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme Corp")
                .platform("GITHUB")
                .build();

        registry.register(config);

        assertTrue(registry.getTenant("acme").isPresent());
        assertEquals("Acme Corp", registry.getTenant("acme").get().getTenantName());
    }

    @Test
    void register_null_tenantId_throws() {
        TenantConfig config = TenantConfig.builder().tenantId(null).build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(config));
    }

    @Test
    void register_blank_tenantId_throws() {
        TenantConfig config = TenantConfig.builder().tenantId("  ").build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(config));
    }

    @Test
    void register_null_config_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void getTenant_unknown_returns_empty() {
        assertTrue(registry.getTenant("unknown").isEmpty());
    }

    @Test
    void getTenant_null_returns_empty() {
        assertTrue(registry.getTenant(null).isEmpty());
    }

    @Test
    void deregister_removes_tenant() {
        TenantConfig config = TenantConfig.builder().tenantId("test").tenantName("Test").build();
        registry.register(config);
        assertTrue(registry.isRegistered("test"));

        boolean removed = registry.deregister("test");
        assertTrue(removed);
        assertFalse(registry.isRegistered("test"));
    }

    @Test
    void deregister_unknown_returns_false() {
        assertFalse(registry.deregister("nonexistent"));
    }

    @Test
    void getActiveTenants_filters_inactive() {
        registry.register(TenantConfig.builder().tenantId("active1").tenantName("A1").active(true).build());
        registry.register(TenantConfig.builder().tenantId("inactive1").tenantName("I1").active(false).build());
        registry.register(TenantConfig.builder().tenantId("active2").tenantName("A2").active(true).build());

        assertEquals(2, registry.getActiveTenants().size());
    }

    @Test
    void size_returns_correct_count() {
        assertEquals(0, registry.size());
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        registry.register(TenantConfig.builder().tenantId("t2").tenantName("T2").build());
        assertEquals(2, registry.size());
    }

    @Test
    void getAllTenants_returns_all_registered() {
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        registry.register(TenantConfig.builder().tenantId("t2").tenantName("T2").build());
        assertEquals(2, registry.getAllTenants().size());
    }
}
