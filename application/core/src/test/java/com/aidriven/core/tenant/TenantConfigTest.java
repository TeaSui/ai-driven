package com.aidriven.core.tenant;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigTest {

    @Test
    void effectiveTriggerLabel_defaults_to_ai_generate() {
        TenantConfig config = TenantConfig.builder().tenantId("t1").build();
        assertEquals("ai-generate", config.effectiveTriggerLabel());
    }

    @Test
    void effectiveTriggerLabel_uses_custom_label() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("t1")
                .triggerLabel("custom-trigger")
                .build();
        assertEquals("custom-trigger", config.effectiveTriggerLabel());
    }

    @Test
    void effectiveAgentTriggerPrefix_defaults_to_at_ai() {
        TenantConfig config = TenantConfig.builder().tenantId("t1").build();
        assertEquals("@ai", config.effectiveAgentTriggerPrefix());
    }

    @Test
    void effectiveAgentTokenBudget_defaults_to_50000() {
        TenantConfig config = TenantConfig.builder().tenantId("t1").build();
        assertEquals(50_000, config.effectiveAgentTokenBudget());
    }

    @Test
    void effectiveAgentTokenBudget_uses_custom_value() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("t1")
                .agentTokenBudget(100_000)
                .build();
        assertEquals(100_000, config.effectiveAgentTokenBudget());
    }

    @Test
    void effectiveAgentMaxTurns_defaults_to_10() {
        TenantConfig config = TenantConfig.builder().tenantId("t1").build();
        assertEquals(10, config.effectiveAgentMaxTurns());
    }

    @Test
    void isPluginEnabled_returns_true_for_enabled_plugin() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("t1")
                .enabledPlugins(Set.of("monitoring", "messaging"))
                .build();
        assertTrue(config.isPluginEnabled("monitoring"));
        assertTrue(config.isPluginEnabled("messaging"));
        assertFalse(config.isPluginEnabled("data"));
    }

    @Test
    void isPluginEnabled_returns_false_when_no_plugins() {
        TenantConfig config = TenantConfig.builder().tenantId("t1").build();
        assertFalse(config.isPluginEnabled("monitoring"));
    }

    @Test
    void fromEnvironment_creates_config_with_defaults() {
        TenantConfig config = TenantConfigLoader.fromEnvironment("test-tenant");
        assertEquals("test-tenant", config.getTenantId());
        assertEquals("ai-generate", config.effectiveTriggerLabel());
        assertEquals("@ai", config.effectiveAgentTriggerPrefix());
        assertEquals(50_000, config.effectiveAgentTokenBudget());
        assertEquals(10, config.effectiveAgentMaxTurns());
        assertTrue(config.isActive());
    }
}
