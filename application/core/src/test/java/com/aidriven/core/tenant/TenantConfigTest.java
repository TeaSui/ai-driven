package com.aidriven.core.tenant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigTest {

    @Test
    void createPk_shouldPrefixWithTenant() {
        assertEquals("TENANT#acme-corp", TenantConfig.createPk("acme-corp"));
    }

    @Test
    void create_shouldPopulateRequiredFields() {
        TenantConfig config = TenantConfig.create("acme-corp", "ACME Corporation", "ENTERPRISE");

        assertEquals("TENANT#acme-corp", config.getPk());
        assertEquals(TenantConfig.CONFIG_SK, config.getSk());
        assertEquals("acme-corp", config.getTenantId());
        assertEquals("ACME Corporation", config.getDisplayName());
        assertEquals("ENTERPRISE", config.getPlan());
        assertTrue(config.isActive());
        assertNotNull(config.getCreatedAt());
        assertNotNull(config.getUpdatedAt());
    }

    @Test
    void isModuleEnabled_withNullModules_defaultsToCoreModes() {
        TenantConfig config = TenantConfig.create("test", "Test", "FREE");
        config.setEnabledModules(null);

        assertTrue(config.isModuleEnabled("source_control"));
        assertTrue(config.isModuleEnabled("issue_tracker"));
        assertTrue(config.isModuleEnabled("code_context"));
        assertFalse(config.isModuleEnabled("monitoring"));
    }

    @Test
    void isModuleEnabled_withExplicitModules_checksListMembership() {
        TenantConfig config = TenantConfig.create("test", "Test", "PROFESSIONAL");
        config.setEnabledModules(List.of("source_control", "issue_tracker", "monitoring"));

        assertTrue(config.isModuleEnabled("source_control"));
        assertTrue(config.isModuleEnabled("monitoring"));
        assertFalse(config.isModuleEnabled("code_context"));
    }

    @Test
    void effectiveAiModel_withTenantOverride_returnsTenantModel() {
        TenantConfig config = TenantConfig.create("test", "Test", "ENTERPRISE");
        config.setAiModel("claude-haiku-4-5");

        assertEquals("claude-haiku-4-5", config.effectiveAiModel("claude-opus-4-6"));
    }

    @Test
    void effectiveAiModel_withoutOverride_returnsGlobalDefault() {
        TenantConfig config = TenantConfig.create("test", "Test", "FREE");
        config.setAiModel(null);

        assertEquals("claude-opus-4-6", config.effectiveAiModel("claude-opus-4-6"));
    }

    @Test
    void effectiveAiModel_withBlankOverride_returnsGlobalDefault() {
        TenantConfig config = TenantConfig.create("test", "Test", "FREE");
        config.setAiModel("  ");

        assertEquals("claude-opus-4-6", config.effectiveAiModel("claude-opus-4-6"));
    }

    @Test
    void effectiveMaxTokensPerTicket_withOverride_returnsTenantValue() {
        TenantConfig config = TenantConfig.create("test", "Test", "ENTERPRISE");
        config.setMaxTokensPerTicket(500_000);

        assertEquals(500_000, config.effectiveMaxTokensPerTicket(200_000));
    }

    @Test
    void effectiveMaxTokensPerTicket_withoutOverride_returnsGlobalDefault() {
        TenantConfig config = TenantConfig.create("test", "Test", "FREE");
        config.setMaxTokensPerTicket(null);

        assertEquals(200_000, config.effectiveMaxTokensPerTicket(200_000));
    }
}
