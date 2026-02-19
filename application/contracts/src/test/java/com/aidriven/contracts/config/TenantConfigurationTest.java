package com.aidriven.contracts.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigurationTest {

    @Test
    void should_return_setting_when_present() {
        TenantConfiguration config = new TenantConfiguration(
                "tenant-1", "Acme Corp",
                null, null, null,
                List.of("monitoring"),
                Map.of("branch_prefix", "ai/", "max_files", "100"));

        assertTrue(config.getSetting("branch_prefix").isPresent());
        assertEquals("ai/", config.getSetting("branch_prefix").get());
    }

    @Test
    void should_return_empty_when_setting_not_present() {
        TenantConfiguration config = new TenantConfiguration(
                "tenant-1", "Acme Corp",
                null, null, null,
                List.of(), Map.of());

        assertTrue(config.getSetting("nonexistent").isEmpty());
    }

    @Test
    void should_return_empty_when_custom_settings_null() {
        TenantConfiguration config = new TenantConfiguration(
                "tenant-1", "Acme Corp",
                null, null, null,
                null, null);

        assertTrue(config.getSetting("any").isEmpty());
    }

    @Test
    void should_check_tool_enabled() {
        TenantConfiguration config = new TenantConfiguration(
                "tenant-1", "Acme Corp",
                null, null, null,
                List.of("monitoring", "messaging"),
                Map.of());

        assertTrue(config.isToolEnabled("monitoring"));
        assertTrue(config.isToolEnabled("messaging"));
        assertFalse(config.isToolEnabled("data"));
    }

    @Test
    void should_return_false_when_enabled_tools_null() {
        TenantConfiguration config = new TenantConfiguration(
                "tenant-1", "Acme Corp",
                null, null, null,
                null, null);

        assertFalse(config.isToolEnabled("monitoring"));
    }

    @Test
    void should_return_false_when_enabled_tools_empty() {
        TenantConfiguration config = new TenantConfiguration(
                "tenant-1", "Acme Corp",
                null, null, null,
                List.of(), null);

        assertFalse(config.isToolEnabled("monitoring"));
    }

    @Test
    void should_create_source_control_config() {
        var scConfig = new TenantConfiguration.SourceControlConfig(
                "GITHUB", "arn:aws:secret", "my-org", "my-repo");

        assertEquals("GITHUB", scConfig.platform());
        assertEquals("arn:aws:secret", scConfig.secretArn());
        assertEquals("my-org", scConfig.defaultWorkspace());
        assertEquals("my-repo", scConfig.defaultRepo());
    }

    @Test
    void should_create_issue_tracker_config() {
        var itConfig = new TenantConfiguration.IssueTrackerConfig(
                "JIRA", "arn:aws:secret", "https://acme.atlassian.net");

        assertEquals("JIRA", itConfig.platform());
        assertEquals("https://acme.atlassian.net", itConfig.baseUrl());
    }

    @Test
    void should_create_ai_model_config() {
        var aiConfig = new TenantConfiguration.AiModelConfig(
                "claude", "arn:aws:secret", "claude-sonnet-4-5", 32768, 0.2);

        assertEquals("claude", aiConfig.provider());
        assertEquals("claude-sonnet-4-5", aiConfig.defaultModel());
        assertEquals(32768, aiConfig.maxTokens());
        assertEquals(0.2, aiConfig.temperature(), 0.001);
    }
}
