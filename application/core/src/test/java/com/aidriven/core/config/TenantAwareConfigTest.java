package com.aidriven.core.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantAwareConfigTest {

    private final AppConfig globalConfig = AppConfig.getInstance();

    @Test
    void should_return_global_default_when_no_override() {
        TenantAwareConfig config = TenantAwareConfig.singleTenant(globalConfig);

        assertEquals(globalConfig.getClaudeModel(), config.getClaudeModel());
        assertEquals(globalConfig.getBranchPrefix(), config.getBranchPrefix());
    }

    @Test
    void should_return_tenant_override_when_present() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("claude_model", "claude-haiku-4-5",
                        "branch_prefix", "tenant-ai/"));

        assertEquals("claude-haiku-4-5", config.getClaudeModel());
        assertEquals("tenant-ai/", config.getBranchPrefix());
    }

    @Test
    void should_return_global_default_for_blank_override() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("claude_model", "  "));

        assertEquals(globalConfig.getClaudeModel(), config.getClaudeModel());
    }

    @Test
    void should_parse_int_override() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("claude_max_tokens", "16384"));

        assertEquals(16384, config.getClaudeMaxTokens());
    }

    @Test
    void should_fallback_on_invalid_int_override() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("claude_max_tokens", "not-a-number"));

        assertEquals(globalConfig.getClaudeMaxTokens(), config.getClaudeMaxTokens());
    }

    @Test
    void should_parse_boolean_override() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("some_flag", "true"));

        assertTrue(config.getBoolean("some_flag", false));
    }

    @Test
    void should_parse_double_override_for_temperature() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("claude_temperature", "0.8"));

        assertEquals(0.8, config.getClaudeTemperature(), 0.001);
    }

    @Test
    void should_fallback_on_invalid_double_override() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("claude_temperature", "invalid"));

        assertEquals(globalConfig.getClaudeTemperature(), config.getClaudeTemperature(), 0.001);
    }

    @Test
    void should_check_has_override() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("claude_model", "custom-model"));

        assertTrue(config.hasOverride("claude_model"));
        assertFalse(config.hasOverride("nonexistent_key"));
    }

    @Test
    void should_handle_null_overrides_as_empty() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig, null);

        assertNotNull(config.getTenantOverrides());
        assertTrue(config.getTenantOverrides().isEmpty());
    }

    @Test
    void should_make_overrides_unmodifiable() {
        Map<String, String> mutable = new java.util.HashMap<>();
        mutable.put("key", "value");

        TenantAwareConfig config = new TenantAwareConfig(globalConfig, mutable);

        assertThrows(UnsupportedOperationException.class,
                () -> config.getTenantOverrides().put("new", "value"));
    }

    @Test
    void should_return_global_config() {
        TenantAwareConfig config = TenantAwareConfig.singleTenant(globalConfig);

        assertSame(globalConfig, config.getGlobalConfig());
    }

    @Test
    void should_override_default_platform() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("default_platform", "GITHUB"));

        assertEquals("GITHUB", config.getDefaultPlatform());
    }

    @Test
    void should_override_max_context() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("max_context_for_claude", "500000"));

        assertEquals(500000, config.getMaxContextForClaude());
    }

    @Test
    void should_override_agent_max_turns() {
        TenantAwareConfig config = new TenantAwareConfig(globalConfig,
                Map.of("agent_max_turns", "20"));

        assertEquals(20, config.getAgentMaxTurns());
    }
}
