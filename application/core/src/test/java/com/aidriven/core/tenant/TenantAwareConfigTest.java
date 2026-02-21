package com.aidriven.core.tenant;

import com.aidriven.core.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantAwareConfigTest {

    private AppConfig globalConfig;

    @BeforeEach
    void setUp() {
        globalConfig = mock(AppConfig.class);
        when(globalConfig.getClaudeModel()).thenReturn("claude-opus-4-6");
        when(globalConfig.getJiraSecretArn()).thenReturn("arn:global:jira");
        when(globalConfig.getBitbucketSecretArn()).thenReturn("arn:global:bitbucket");
        when(globalConfig.getGitHubSecretArn()).thenReturn("arn:global:github");
        when(globalConfig.getDefaultPlatform()).thenReturn("BITBUCKET");
        when(globalConfig.getDefaultWorkspace()).thenReturn("global-ws");
        when(globalConfig.getDefaultRepo()).thenReturn("global-repo");
        when(globalConfig.getMcpServersConfig()).thenReturn("[]");
        var agentConfig = new com.aidriven.core.config.AgentConfig(
                true, "queue-url", 10, 600, "@ai", 50000, 2,
                true, 200_000, false);
        when(globalConfig.getAgentConfig()).thenReturn(agentConfig);
    }

    @Test
    void withGlobalDefaults_usesGlobalConfigForAllSettings() {
        TenantAwareConfig config = TenantAwareConfig.withGlobalDefaults(globalConfig);

        assertEquals("claude-opus-4-6", config.getAiModel());
        assertEquals("arn:global:jira", config.getIssueTrackerSecretArn());
        assertEquals("BITBUCKET", config.getSourceControlType());
        assertEquals("global-ws", config.getDefaultWorkspace());
        assertEquals("global-repo", config.getDefaultRepo());
        assertEquals(200_000, config.getMaxTokensPerTicket());
    }

    @Test
    void tenantOverride_aiModel_overridesGlobal() {
        TenantConfig tenant = TenantConfig.create("acme", "ACME", "ENTERPRISE");
        tenant.setAiModel("claude-haiku-4-5");

        TenantAwareConfig config = new TenantAwareConfig(globalConfig, tenant);
        assertEquals("claude-haiku-4-5", config.getAiModel());
    }

    @Test
    void tenantOverride_issueTrackerSecretArn_overridesGlobal() {
        TenantConfig tenant = TenantConfig.create("acme", "ACME", "ENTERPRISE");
        tenant.setIssueTrackerSecretArn("arn:tenant:jira");

        TenantAwareConfig config = new TenantAwareConfig(globalConfig, tenant);
        assertEquals("arn:tenant:jira", config.getIssueTrackerSecretArn());
    }

    @Test
    void tenantOverride_sourceControlGitHub_returnsGitHubArn() {
        TenantConfig tenant = TenantConfig.create("acme", "ACME", "ENTERPRISE");
        tenant.setSourceControlType("GITHUB");
        // No explicit secret ARN — should fall back to global GitHub ARN

        TenantAwareConfig config = new TenantAwareConfig(globalConfig, tenant);
        assertEquals("GITHUB", config.getSourceControlType());
        assertEquals("arn:global:github", config.getSourceControlSecretArn());
    }

    @Test
    void tenantOverride_enabledModules_restrictedSet() {
        TenantConfig tenant = TenantConfig.create("acme", "ACME", "STARTER");
        tenant.setEnabledModules(List.of("source_control", "issue_tracker"));

        TenantAwareConfig config = new TenantAwareConfig(globalConfig, tenant);
        assertTrue(config.isModuleEnabled("source_control"));
        assertTrue(config.isModuleEnabled("issue_tracker"));
        assertFalse(config.isModuleEnabled("code_context"));
        assertFalse(config.isModuleEnabled("monitoring"));
    }

    @Test
    void tenantOverride_maxTokensPerTicket_overridesGlobal() {
        TenantConfig tenant = TenantConfig.create("acme", "ACME", "ENTERPRISE");
        tenant.setMaxTokensPerTicket(1_000_000);

        TenantAwareConfig config = new TenantAwareConfig(globalConfig, tenant);
        assertEquals(1_000_000, config.getMaxTokensPerTicket());
    }

    @Test
    void isModuleEnabled_withNullTenant_defaultsToCoreModes() {
        TenantAwareConfig config = TenantAwareConfig.withGlobalDefaults(globalConfig);

        assertTrue(config.isModuleEnabled("source_control"));
        assertTrue(config.isModuleEnabled("issue_tracker"));
        assertTrue(config.isModuleEnabled("code_context"));
        assertFalse(config.isModuleEnabled("monitoring"));
    }
}
