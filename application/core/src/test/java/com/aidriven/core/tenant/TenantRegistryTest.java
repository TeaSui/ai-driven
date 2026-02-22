package com.aidriven.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TenantRegistryTest {

    private TenantRegistry registry;

    @BeforeEach
    void setUp() {
        // Use a fresh instance per test (not the singleton) to avoid state leakage
        registry = new TenantRegistry() {};
        // Actually use a local instance via reflection or just clear the singleton
        TenantRegistry.getInstance().clear();
        registry = TenantRegistry.getInstance();
    }

    @Test
    void register_and_retrieve_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme Corp")
                .jiraSecretArn("arn:aws:secretsmanager:us-east-1:123:secret:acme-jira")
                .defaultPlatform("BITBUCKET")
                .build();

        registry.register(config);

        Optional<TenantConfig> result = registry.getTenant("acme");
        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getTenantId());
        assertEquals("Acme Corp", result.get().getTenantName());
    }

    @Test
    void getTenant_returns_empty_for_unknown_tenant() {
        Optional<TenantConfig> result = registry.getTenant("unknown");
        assertFalse(result.isPresent());
    }

    @Test
    void getTenant_returns_empty_for_null_id() {
        Optional<TenantConfig> result = registry.getTenant(null);
        assertFalse(result.isPresent());
    }

    @Test
    void register_throws_for_null_config() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void register_throws_for_blank_tenant_id() {
        TenantConfig config = TenantConfig.builder().tenantId("").tenantName("Test").build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(config));
    }

    @Test
    void register_replaces_existing_tenant() {
        TenantConfig v1 = TenantConfig.builder().tenantId("acme").tenantName("Acme v1").build();
        TenantConfig v2 = TenantConfig.builder().tenantId("acme").tenantName("Acme v2").build();

        registry.register(v1);
        registry.register(v2);

        assertEquals("Acme v2", registry.getTenant("acme").get().getTenantName());
        assertEquals(1, registry.size());
    }

    @Test
    void hasTenant_returns_true_when_registered() {
        registry.register(TenantConfig.builder().tenantId("test").tenantName("Test").build());
        assertTrue(registry.hasTenant("test"));
    }

    @Test
    void hasTenant_returns_false_when_not_registered() {
        assertFalse(registry.hasTenant("nonexistent"));
    }

    @Test
    void deregister_removes_tenant() {
        registry.register(TenantConfig.builder().tenantId("acme").tenantName("Acme").build());
        registry.deregister("acme");
        assertFalse(registry.hasTenant("acme"));
    }

    @Test
    void getAllTenants_returns_all_registered() {
        registry.register(TenantConfig.builder().tenantId("acme").tenantName("Acme").build());
        registry.register(TenantConfig.builder().tenantId("beta").tenantName("Beta").build());
        assertEquals(2, registry.getAllTenants().size());
    }

    @Test
    void size_reflects_registered_count() {
        assertEquals(0, registry.size());
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        assertEquals(1, registry.size());
    }
}
