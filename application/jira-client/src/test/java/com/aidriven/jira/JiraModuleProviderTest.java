package com.aidriven.jira;

import com.aidriven.spi.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JiraModuleProviderTest {

    @Test
    void should_return_correct_descriptor() {
        JiraModuleProvider provider = new JiraModuleProvider();
        ModuleDescriptor desc = provider.descriptor();

        assertEquals("jira-client", desc.id());
        assertEquals(ModuleCategory.ISSUE_TRACKER, desc.category());
        assertTrue(desc.requiredConfigs().contains("jira.secretArn"));
        assertTrue(desc.capabilities().contains("issue-tracking"));
    }

    @Test
    void should_not_be_initialized_by_default() {
        JiraModuleProvider provider = new JiraModuleProvider();
        assertFalse(provider.isInitialized());
        assertEquals(HealthStatus.Status.NOT_INITIALIZED, provider.healthCheck().status());
    }

    @Test
    void should_initialize_with_valid_config() throws Exception {
        JiraModuleProvider provider = new JiraModuleProvider();
        TenantContext context = TenantContext.builder("t1")
                .config("jira.secretArn", "arn:aws:secret:jira")
                .build();

        provider.initialize(context);

        assertTrue(provider.isInitialized());
        assertTrue(provider.healthCheck().isHealthy());
    }

    @Test
    void should_fail_initialization_without_secret_arn() {
        JiraModuleProvider provider = new JiraModuleProvider();
        TenantContext context = TenantContext.builder("t1").build();

        assertThrows(ModuleInitializationException.class, () ->
                provider.initialize(context));
    }

    @Test
    void should_shutdown_cleanly() throws Exception {
        JiraModuleProvider provider = new JiraModuleProvider();
        TenantContext context = TenantContext.builder("t1")
                .config("jira.secretArn", "arn:aws:secret:jira")
                .build();

        provider.initialize(context);
        assertTrue(provider.isInitialized());

        provider.shutdown();
        assertFalse(provider.isInitialized());
    }
}
