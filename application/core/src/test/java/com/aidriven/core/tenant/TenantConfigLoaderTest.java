package com.aidriven.core.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigLoaderTest {

    private TenantConfigLoader loader;
    private TenantRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        loader = new TenantConfigLoader(objectMapper);
        registry = TenantRegistry.getInstance();
        registry.clear();
    }

    @Test
    void loadInto_single_tenant_mode_creates_default_tenant() {
        loader.loadInto(registry,
                "arn:jira", "arn:bb", null, "arn:claude",
                "BITBUCKET", "my-workspace", "my-repo");

        assertEquals(1, registry.size());
        TenantConfig tenant = registry.getAllTenants().iterator().next();
        assertEquals("arn:jira", tenant.getJiraSecretArn());
        assertEquals("arn:bb", tenant.getBitbucketSecretArn());
        assertEquals("BITBUCKET", tenant.getDefaultPlatform());
        assertEquals("my-workspace", tenant.getDefaultWorkspace());
        assertEquals("my-repo", tenant.getDefaultRepo());
    }

    @Test
    void tenantConfig_defaults_are_correct() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("test")
                .tenantName("Test")
                .build();

        assertEquals("BITBUCKET", config.getDefaultPlatform());
        assertEquals("ai/", config.getBranchPrefix());
        assertTrue(config.isGuardrailsEnabled());
        assertEquals(10, config.getMaxAgentTurns());
        assertEquals(200_000, config.getTokenBudgetPerTicket());
        assertTrue(config.getEnabledTools().isEmpty());
        assertEquals("[]", config.getMcpServersConfig());
    }

    @Test
    void tenantConfig_hasGitHub_returns_false_when_no_secret() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("test")
                .tenantName("Test")
                .build();
        assertFalse(config.hasGitHub());
    }

    @Test
    void tenantConfig_hasGitHub_returns_true_when_secret_set() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("test")
                .tenantName("Test")
                .gitHubSecretArn("arn:aws:secretsmanager:us-east-1:123:secret:github")
                .build();
        assertTrue(config.hasGitHub());
    }

    @Test
    void tenantConfig_hasBitbucket_returns_true_when_secret_set() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("test")
                .tenantName("Test")
                .bitbucketSecretArn("arn:aws:secretsmanager:us-east-1:123:secret:bb")
                .build();
        assertTrue(config.hasBitbucket());
    }

    @Test
    void tenantConfig_isToolEnabled_returns_true_for_enabled_tool() {
        TenantConfig config = TenantConfig.builder()
                .tenantId("test")
                .tenantName("Test")
                .enabledTools(java.util.Set.of("monitoring", "messaging"))
                .build();
        assertTrue(config.isToolEnabled("monitoring"));
        assertFalse(config.isToolEnabled("data"));
    }
}
