package com.aidriven.core.plugin;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginRegistryTest {

    private SecretsService secretsService;
    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        secretsService = mock(SecretsService.class);
    }

    @Test
    void should_initialize_enabled_plugins_only() {
        PluginModule enabledPlugin = createMockPlugin("jira", true);
        PluginModule disabledPlugin = createMockPlugin("slack", true);

        registry = new PluginRegistry(secretsService, List.of(enabledPlugin, disabledPlugin));

        TenantContext ctx = new TenantContext(
                "t1", "Test", "prod",
                Map.of(), Map.of(),
                Map.of("jira", true, "slack", false));

        List<ToolProvider> providers = registry.getToolProviders(ctx);

        // Only jira plugin should be initialized
        verify(enabledPlugin).initialize(ctx, secretsService);
        verify(disabledPlugin, never()).initialize(any(), any());
        assertEquals(1, providers.size());
    }

    @Test
    void should_cache_initialized_plugins_per_tenant() {
        PluginModule plugin = createMockPlugin("jira", true);
        registry = new PluginRegistry(secretsService, List.of(plugin));

        TenantContext ctx = new TenantContext(
                "t1", "Test", "prod",
                Map.of(), Map.of(), Map.of("jira", true));

        registry.getToolProviders(ctx);
        registry.getToolProviders(ctx);

        // Should only initialize once
        verify(plugin, times(1)).initialize(any(), any());
    }

    @Test
    void should_isolate_plugins_between_tenants() {
        PluginModule plugin = createMockPlugin("jira", true);
        registry = new PluginRegistry(secretsService, List.of(plugin));

        TenantContext ctx1 = new TenantContext(
                "t1", "Tenant 1", "prod",
                Map.of(), Map.of(), Map.of("jira", true));
        TenantContext ctx2 = new TenantContext(
                "t2", "Tenant 2", "prod",
                Map.of(), Map.of(), Map.of("jira", true));

        registry.getToolProviders(ctx1);
        registry.getToolProviders(ctx2);

        // Should initialize for each tenant separately
        verify(plugin, times(2)).initialize(any(), any());
    }

    @Test
    void should_handle_plugin_initialization_failure_gracefully() {
        PluginModule failingPlugin = createMockPlugin("broken", false);
        doThrow(new RuntimeException("Init failed"))
                .when(failingPlugin).initialize(any(), any());

        PluginModule workingPlugin = createMockPlugin("jira", true);

        registry = new PluginRegistry(secretsService, List.of(failingPlugin, workingPlugin));

        TenantContext ctx = new TenantContext(
                "t1", "Test", "prod",
                Map.of(), Map.of(),
                Map.of("broken", true, "jira", true));

        List<ToolProvider> providers = registry.getToolProviders(ctx);

        // Only working plugin should contribute providers
        assertEquals(1, providers.size());
    }

    @Test
    void should_shutdown_tenant_plugins() {
        PluginModule plugin = createMockPlugin("jira", true);
        registry = new PluginRegistry(secretsService, List.of(plugin));

        TenantContext ctx = new TenantContext(
                "t1", "Test", "prod",
                Map.of(), Map.of(), Map.of("jira", true));

        registry.getToolProviders(ctx);
        registry.shutdownTenant("t1");

        verify(plugin).shutdown();
    }

    @Test
    void should_return_available_plugin_descriptors() {
        PluginModule plugin1 = createMockPlugin("jira", true);
        PluginModule plugin2 = createMockPlugin("github", true);

        registry = new PluginRegistry(secretsService, List.of(plugin1, plugin2));

        List<PluginDescriptor> descriptors = registry.getAvailablePlugins();

        assertEquals(2, descriptors.size());
    }

    @Test
    void should_return_enabled_plugins_for_tenant() {
        PluginModule jira = createMockPlugin("jira", true);
        PluginModule github = createMockPlugin("github", true);

        registry = new PluginRegistry(secretsService, List.of(jira, github));

        TenantContext ctx = new TenantContext(
                "t1", "Test", "prod",
                Map.of(), Map.of(),
                Map.of("jira", true, "github", false));

        List<PluginDescriptor> enabled = registry.getEnabledPlugins(ctx);

        assertEquals(1, enabled.size());
        assertEquals("jira", enabled.get(0).id());
    }

    @Test
    void should_return_empty_providers_when_no_modules_enabled() {
        PluginModule plugin = createMockPlugin("jira", true);
        registry = new PluginRegistry(secretsService, List.of(plugin));

        TenantContext ctx = new TenantContext(
                "t1", "Test", "prod",
                Map.of(), Map.of(), Map.of());

        List<ToolProvider> providers = registry.getToolProviders(ctx);

        assertTrue(providers.isEmpty());
    }

    private PluginModule createMockPlugin(String id, boolean hasProviders) {
        PluginModule plugin = mock(PluginModule.class);
        when(plugin.descriptor()).thenReturn(new PluginDescriptor(
                id, id + " Plugin", "1.0.0", Set.of(id), "Test plugin"));

        if (hasProviders) {
            ToolProvider mockProvider = mock(ToolProvider.class);
            when(mockProvider.namespace()).thenReturn(id);
            when(mockProvider.toolDefinitions()).thenReturn(List.of(
                    new Tool(id + "_test", "Test tool", Map.of("type", "object", "properties", Map.of()))));
            when(plugin.getToolProviders()).thenReturn(List.of(mockProvider));
        } else {
            when(plugin.getToolProviders()).thenReturn(List.of());
        }

        return plugin;
    }
}