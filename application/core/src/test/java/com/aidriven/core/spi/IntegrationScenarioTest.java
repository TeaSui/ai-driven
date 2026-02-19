package com.aidriven.core.spi;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.core.agent.AiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration test demonstrating the full multi-tenant module system.
 * Simulates two companies with different tool preferences.
 */
class IntegrationScenarioTest {

    private ServiceProviderRegistry registry;
    private ModuleLoader loader;
    private TenantAwareProviderResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new ServiceProviderRegistry();
        loader = new ModuleLoader(registry);
        resolver = new TenantAwareProviderResolver(registry);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        loader.shutdownAll();
    }

    @Test
    void scenario_two_tenants_different_source_control() {
        // Setup: Register both source control providers
        SourceControlClient bbClient = mock(SourceControlClient.class);
        SourceControlClient ghClient = mock(SourceControlClient.class);
        IssueTrackerClient jiraClient = mock(IssueTrackerClient.class);

        registry.registerDefault(SourceControlClient.class, "bitbucket", bbClient);
        registry.register(SourceControlClient.class, "github", ghClient);
        registry.registerDefault(IssueTrackerClient.class, "jira", jiraClient);

        // Tenant A: Uses Bitbucket (default)
        TenantContext.set(TenantContext.of("company-a", Map.of()));
        SourceControlClient tenantAClient = resolver.resolve(SourceControlClient.class, "source_control");
        assertSame(bbClient, tenantAClient, "Company A should use Bitbucket (default)");

        // Tenant B: Uses GitHub
        TenantContext.set(TenantContext.of("company-b", Map.of("source_control", "github")));
        SourceControlClient tenantBClient = resolver.resolve(SourceControlClient.class, "source_control");
        assertSame(ghClient, tenantBClient, "Company B should use GitHub");

        // Both tenants share the same Jira client
        IssueTrackerClient tenantAJira = resolver.resolve(IssueTrackerClient.class, "issue_tracker");
        TenantContext.set(TenantContext.of("company-b", Map.of("source_control", "github")));
        IssueTrackerClient tenantBJira = resolver.resolve(IssueTrackerClient.class, "issue_tracker");
        assertSame(tenantAJira, tenantBJira, "Both tenants should share Jira");
    }

    @Test
    void scenario_module_loader_with_selective_initialization() {
        // Simulate: Company A only needs Bitbucket + Jira
        // Company B needs GitHub + Jira

        ModuleDescriptor bbModule = new ModuleDescriptor() {
            @Override public String name() { return "bitbucket"; }
            @Override public String version() { return "1.0.0"; }
            @Override public List<Class<?>> providedServices() { return List.of(SourceControlClient.class); }
            @Override public List<String> requiredConfigKeys() { return List.of("BB_TOKEN"); }
            @Override public void initialize(ServiceProviderRegistry r, Map<String, String> c) {
                r.registerDefault(SourceControlClient.class, "bitbucket", mock(SourceControlClient.class));
            }
        };

        ModuleDescriptor ghModule = new ModuleDescriptor() {
            @Override public String name() { return "github"; }
            @Override public String version() { return "1.0.0"; }
            @Override public List<Class<?>> providedServices() { return List.of(SourceControlClient.class); }
            @Override public List<String> requiredConfigKeys() { return List.of("GH_TOKEN"); }
            @Override public void initialize(ServiceProviderRegistry r, Map<String, String> c) {
                r.register(SourceControlClient.class, "github", mock(SourceControlClient.class));
            }
        };

        // Company A config: only has BB_TOKEN → only Bitbucket loads
        Map<String, String> companyAConfig = Map.of("BB_TOKEN", "bb-secret");
        int loaded = loader.initializeModules(List.of(bbModule, ghModule), companyAConfig);

        assertEquals(1, loaded, "Only Bitbucket should load (GitHub missing GH_TOKEN)");
        assertTrue(registry.isRegistered(SourceControlClient.class, "bitbucket"));
        assertFalse(registry.isRegistered(SourceControlClient.class, "github"));
    }

    @Test
    void scenario_label_override_takes_precedence() {
        SourceControlClient bbClient = mock(SourceControlClient.class);
        SourceControlClient ghClient = mock(SourceControlClient.class);

        registry.registerDefault(SourceControlClient.class, "bitbucket", bbClient);
        registry.register(SourceControlClient.class, "github", ghClient);

        // Tenant prefers Bitbucket, but ticket has platform:github label
        TenantContext.set(TenantContext.of("company-a", Map.of("source_control", "bitbucket")));

        SourceControlClient result = resolver.resolve(
                SourceControlClient.class, "source_control", "github");

        assertSame(ghClient, result, "Label override should take precedence over tenant preference");
    }
}
