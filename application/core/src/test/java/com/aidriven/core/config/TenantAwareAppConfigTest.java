package com.aidriven.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantAwareAppConfigTest {

    private AppConfig baseConfig;
    private TenantAwareAppConfig tenantConfig;

    @BeforeEach
    void setUp() {
        baseConfig = AppConfig.getInstance();
    }

    @Test
    void should_return_tenant_id() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "acme", Map.of());
        assertEquals("acme", tenantConfig.getTenantId());
    }

    @Test
    void should_use_base_defaults_when_no_overrides() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test", Map.of());

        assertEquals(baseConfig.getClaudeModel(), tenantConfig.getClaudeModel());
        assertEquals(baseConfig.getClaudeMaxTokens(), tenantConfig.getClaudeMaxTokens());
        assertEquals(baseConfig.getClaudeTemperature(), tenantConfig.getClaudeTemperature(), 0.001);
        assertEquals(baseConfig.getBranchPrefix(), tenantConfig.getBranchPrefix());
    }

    @Test
    void should_override_claude_model() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test",
                Map.of("claude.model", "claude-sonnet-4-5"));

        assertEquals("claude-sonnet-4-5", tenantConfig.getClaudeModel());
    }

    @Test
    void should_override_branch_prefix() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test",
                Map.of("branch.prefix", "acme/ai/"));

        assertEquals("acme/ai/", tenantConfig.getBranchPrefix());
    }

    @Test
    void should_override_max_tokens() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test",
                Map.of("claude.maxTokens", "16384"));

        assertEquals(16384, tenantConfig.getClaudeMaxTokens());
    }

    @Test
    void should_override_platform() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test",
                Map.of("platform.default", "GITHUB"));

        assertEquals("GITHUB", tenantConfig.getDefaultPlatform());
    }

    @Test
    void should_override_repo_settings() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test",
                Map.of("repo.workspace", "acme-org", "repo.slug", "backend"));

        assertEquals("acme-org", tenantConfig.getDefaultWorkspace());
        assertEquals("backend", tenantConfig.getDefaultRepo());
    }

    @Test
    void should_ignore_blank_overrides() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test",
                Map.of("claude.model", "  "));

        // Should fall back to base config
        assertEquals(baseConfig.getClaudeModel(), tenantConfig.getClaudeModel());
    }

    @Test
    void should_handle_null_overrides_map() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test", null);

        assertEquals(baseConfig.getClaudeModel(), tenantConfig.getClaudeModel());
    }

    @Test
    void should_always_use_base_for_infrastructure() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test",
                Map.of("claude.model", "override"));

        // Infrastructure settings always come from base
        assertEquals(baseConfig.getDynamoDbTableName(), tenantConfig.getDynamoDbTableName());
        assertEquals(baseConfig.getCodeContextBucket(), tenantConfig.getCodeContextBucket());
    }

    @Test
    void should_expose_base_config() {
        tenantConfig = new TenantAwareAppConfig(baseConfig, "test", Map.of());
        assertSame(baseConfig, tenantConfig.getBaseConfig());
    }
}
