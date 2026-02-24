package com.aidriven.core.tenant;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigTest {

    @Test
    void isPluginEnabled_returns_true_for_enabled_plugin() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .enabledPlugins(Set.of("slack", "sonar"))
                .build();

        assertTrue(config.isPluginEnabled("slack"));
        assertTrue(config.isPluginEnabled("sonar"));
        assertFalse(config.isPluginEnabled("jira-xray"));
    }

    @Test
    void isPluginEnabled_returns_false_when_plugins_null() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .build();

        assertFalse(config.isPluginEnabled("slack"));
    }

    @Test
    void getPluginConfig_returns_config_for_plugin() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .pluginConfig(Map.of("slack", Map.of("channel", "#dev", "token", "xoxb-123")))
                .build();

        Map<String, String> slackConf = config.getPluginConfig("slack");
        assertEquals("#dev", slackConf.get("channel"));
        assertEquals("xoxb-123", slackConf.get("token"));
    }

    @Test
    void getPluginConfig_returns_empty_map_for_unknown_plugin() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .build();

        assertTrue(config.getPluginConfig("unknown").isEmpty());
    }

    @Test
    void getEffectiveClaudeModel_uses_override_when_set() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .claudeModelOverride("claude-haiku-4-5")
                .build();

        assertEquals("claude-haiku-4-5", config.getEffectiveClaudeModel("claude-opus-4-6"));
    }

    @Test
    void getEffectiveClaudeModel_uses_system_default_when_no_override() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .build();

        assertEquals("claude-opus-4-6", config.getEffectiveClaudeModel("claude-opus-4-6"));
    }

    @Test
    void defaults_are_applied_correctly() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .build();

        assertEquals(10, config.getMaxConcurrentWorkflows());
        assertFalse(config.isAgentEnabled());
        assertEquals("ai/", config.getBranchPrefix());
        assertFalse(config.isForceDryRun());
    }

    @Test
    void forceDryRun_can_be_set_true() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .forceDryRun(true)
                .build();

        assertTrue(config.isForceDryRun());
    }
}
