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
    void register_and_lookup_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("ACME Corp")
                .active(true)
                .build();

        registry.register(config);

        assertTrue(registry.getTenant("acme").isPresent());
        assertEquals("ACME Corp", registry.getTenant("acme").get().getTenantName());
    }

    @Test
    void register_null_config_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void register_blank_tenantId_throws() {
        TenantConfig config = TenantConfig.builder().tenantId("").build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(config));
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
    void contains_returns_true_for_registered() {
        registry.register(TenantConfig.builder().tenantId("t1").active(true).build());
        assertTrue(registry.contains("t1"));
        assertFalse(registry.contains("t2"));
    }

    @Test
    void deregister_removes_tenant() {
        registry.register(TenantConfig.builder().tenantId("t1").active(true).build());
        registry.deregister("t1");
        assertFalse(registry.contains("t1"));
    }

    @Test
    void size_reflects_registered_count() {
        assertEquals(0, registry.size());
        registry.register(TenantConfig.builder().tenantId("t1").active(true).build());
        registry.register(TenantConfig.builder().tenantId("t2").active(true).build());
        assertEquals(2, registry.size());
    }

    @Test
    void update_replaces_existing_tenant() {
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("Old Name").active(true).build());
        registry.update(TenantConfig.builder().tenantId("t1").tenantName("New Name").active(true).build());
        assertEquals("New Name", registry.getTenant("t1").get().getTenantName());
        assertEquals(1, registry.size());
    }

    @Test
    void getAllTenants_returns_all() {
        registry.register(TenantConfig.builder().tenantId("t1").active(true).build());
        registry.register(TenantConfig.builder().tenantId("t2").active(true).build());
        assertEquals(2, registry.getAllTenants().size());
    }
}
