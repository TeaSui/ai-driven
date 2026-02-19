package com.aidriven.tenant;

import com.aidriven.contracts.config.TenantConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentTenantResolverTest {

    @Test
    void should_return_default_config_for_any_project_key() {
        TenantConfiguration config = new TenantConfiguration(
                "test-tenant", "Test",
                null, null, null,
                List.of("source_control"), Map.of());

        EnvironmentTenantResolver resolver = new EnvironmentTenantResolver(config);

        assertTrue(resolver.resolveByProjectKey("PROJ").isPresent());
        assertEquals("test-tenant", resolver.resolveByProjectKey("PROJ").get().tenantId());
    }

    @Test
    void should_return_default_config_for_any_tenant_id() {
        TenantConfiguration config = new TenantConfiguration(
                "test-tenant", "Test",
                null, null, null,
                List.of(), Map.of());

        EnvironmentTenantResolver resolver = new EnvironmentTenantResolver(config);

        assertTrue(resolver.resolveById("any-id").isPresent());
        assertEquals("test-tenant", resolver.resolveById("any-id").get().tenantId());
    }

    @Test
    void should_return_same_default_config() {
        TenantConfiguration config = new TenantConfiguration(
                "default", "Default",
                new TenantConfiguration.SourceControlConfig("GITHUB", "arn", "org", "repo"),
                new TenantConfiguration.IssueTrackerConfig("JIRA", "arn", "https://jira.example.com"),
                new TenantConfiguration.AiModelConfig("claude", "arn", "claude-sonnet-4-5", 32768, 0.2),
                List.of("source_control", "issue_tracker"),
                Map.of("branch_prefix", "ai/"));

        EnvironmentTenantResolver resolver = new EnvironmentTenantResolver(config);

        TenantConfiguration result = resolver.getDefault();
        assertEquals("default", result.tenantId());
        assertEquals("GITHUB", result.sourceControl().platform());
        assertEquals("JIRA", result.issueTracker().platform());
        assertEquals("claude", result.aiModel().provider());
        assertTrue(result.isToolEnabled("source_control"));
        assertEquals("ai/", result.getSetting("branch_prefix").orElse(""));
    }

    @Test
    void should_build_from_environment_without_errors() {
        // This tests the no-arg constructor which reads from env
        // In test env, all env vars are null, so defaults should be used
        EnvironmentTenantResolver resolver = new EnvironmentTenantResolver();

        TenantConfiguration config = resolver.getDefault();
        assertNotNull(config);
        assertEquals("default", config.tenantId());
        assertNotNull(config.sourceControl());
        assertEquals("BITBUCKET", config.sourceControl().platform());
        assertNotNull(config.issueTracker());
        assertNotNull(config.aiModel());
        assertEquals("claude-opus-4-6", config.aiModel().defaultModel());
    }
}
