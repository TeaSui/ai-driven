package com.aidriven.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void register_and_retrieve_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme Corp")
                .defaultPlatform("GITHUB")
                .build();

        registry.register(config);

        Optional<TenantConfig> result = registry.getTenant("acme");
        assertTrue(result.isPresent());
        assertEquals("Acme Corp", result.get().getTenantName());
    }

    @Test
    void getTenant_returns_empty_for_unknown_id() {
        assertTrue(registry.getTenant("unknown").isEmpty());
    }

    @Test
    void getTenant_returns_empty_for_null_id() {
        assertTrue(registry.getTenant(null).isEmpty());
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
        TenantConfig config = TenantConfig.builder().tenantId("temp").tenantName("Temp").build();
        registry.register(config);
        assertTrue(registry.deregister("temp"));
        assertTrue(registry.getTenant("temp").isEmpty());
    }

    @Test
    void deregister_returns_false_for_unknown_tenant() {
        assertFalse(registry.deregister("nonexistent"));
    }

    @Test
    void resolveByJiraProject_finds_matching_tenant() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .jiraProjectKeys(List.of("ACME", "PROJ"))
                .build();
        registry.register(config);

        Optional<TenantConfig> result = registry.resolveByJiraProject("ACME");
        assertTrue(result.isPresent());
        assertEquals("acme", result.get().getTenantId());
    }

    @Test
    void resolveByJiraProject_returns_empty_when_no_match() {
        assertTrue(registry.resolveByJiraProject("UNKNOWN").isEmpty());
    }

    @Test
    void getAllTenants_returns_all_registered() {
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        registry.register(TenantConfig.builder().tenantId("t2").tenantName("T2").build());

        assertEquals(2, registry.getAllTenants().size());
    }

    @Test
    void size_reflects_registered_count() {
        assertEquals(0, registry.size());
        registry.register(TenantConfig.builder().tenantId("t1").tenantName("T1").build());
        assertEquals(1, registry.size());
    }
}
