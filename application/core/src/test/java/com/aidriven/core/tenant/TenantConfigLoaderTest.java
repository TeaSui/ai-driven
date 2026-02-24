package com.aidriven.core.tenant;

import com.aidriven.core.service.SecretsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TenantConfigLoaderTest {

    private TenantConfigLoader loader;
    private TenantRegistry registry;

    @BeforeEach
    void setUp() {
        SecretsService secretsService = mock(SecretsService.class);
        loader = new TenantConfigLoader(new ObjectMapper(), secretsService);
        registry = new TenantRegistry();
    }

    @Test
    void should_load_default_tenant_when_no_env_config() {
        // No TENANT_CONFIGS env var set in test environment
        int count = loader.loadFromEnvironment(registry);

        assertEquals(1, count);
        assertFalse(registry.getAllTenants().isEmpty());
    }

    @Test
    void should_return_default_tenant_id() {
        // DEFAULT_TENANT_ID not set in test environment
        String defaultId = loader.getDefaultTenantId();
        assertEquals("default", defaultId);
    }

    @Test
    void should_create_default_tenant_config() {
        TenantConfig config = TenantConfig.defaultTenant("test-tenant");

        assertEquals("test-tenant", config.getTenantId());
        assertEquals("test-tenant", config.getTenantName());
        assertTrue(config.isActive());
        assertTrue(config.isGuardrailsEnabled());
        assertEquals(200_000, config.getTokenBudget());
        assertEquals(10, config.getMaxTurns());
    }

    @Test
    void should_check_feature_flag() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("t1")
                .featureFlags(java.util.Map.of("smart-context", true, "dry-run", false))
                .build();

        assertTrue(config.isFeatureEnabled("smart-context"));
        assertFalse(config.isFeatureEnabled("dry-run"));
        assertFalse(config.isFeatureEnabled("unknown-flag"));
    }

    @Test
    void should_return_effective_max_turns() {
        TenantConfig withTurns = TenantConfig.builder().tenantId("t1").maxTurns(20).build();
        TenantConfig noTurns = TenantConfig.builder().tenantId("t2").maxTurns(0).build();

        assertEquals(20, withTurns.getEffectiveMaxTurns(10));
        assertEquals(10, noTurns.getEffectiveMaxTurns(10));
    }
}
