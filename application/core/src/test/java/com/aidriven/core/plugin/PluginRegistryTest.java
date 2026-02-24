package com.aidriven.core.plugin;

import com.aidriven.core.agent.tool.Schema;
import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.tenant.TenantConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {

    private PluginRegistry pluginRegistry;

    @BeforeEach
    void setUp() {
        pluginRegistry = new PluginRegistry();
    }

    @Test
    void should_register_and_retrieve_plugin() {
        WorkflowPlugin plugin = createPlugin("monitoring", "Monitoring Plugin");
        pluginRegistry.register(plugin);

        assertTrue(pluginRegistry.getPlugin("monitoring").isPresent());
        assertEquals(1, pluginRegistry.size());
    }

    @Test
    void should_throw_for_null_plugin() {
        assertThrows(IllegalArgumentException.class, () -> pluginRegistry.register(null));
    }

    @Test
    void should_throw_for_blank_namespace() {
        WorkflowPlugin plugin = createPlugin("", "Bad Plugin");
        assertThrows(IllegalArgumentException.class, () -> pluginRegistry.register(plugin));
    }

    @Test
    void should_deregister_plugin() {
        pluginRegistry.register(createPlugin("monitoring", "Monitoring"));
        assertTrue(pluginRegistry.deregister("monitoring"));
        assertTrue(pluginRegistry.getPlugin("monitoring").isEmpty());
    }

    @Test
    void should_return_false_deregistering_unknown() {
        assertFalse(pluginRegistry.deregister("unknown"));
    }

    @Test
    void should_build_tool_registry_with_enabled_plugins() {
        pluginRegistry.register(createPlugin("monitoring", "Monitoring"));
        pluginRegistry.register(createPlugin("messaging", "Messaging"));

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("t1")
                .active(true)
                .enabledPlugins(Set.of("monitoring")) // only monitoring enabled
                .build();

        ToolProvider coreProvider = createToolProvider("source_control");
        ToolRegistry toolRegistry = pluginRegistry.buildToolRegistry(tenant, List.of(coreProvider));

        Set<String> namespaces = toolRegistry.getRegisteredNamespaces();
        assertTrue(namespaces.contains("source_control"), "Core provider should always be included");
        assertTrue(namespaces.contains("monitoring"), "Enabled plugin should be included");
        assertFalse(namespaces.contains("messaging"), "Disabled plugin should be excluded");
    }

    @Test
    void should_skip_incompatible_plugins() {
        WorkflowPlugin incompatible = new WorkflowPlugin() {
            public String namespace() { return "incompatible"; }
            public String displayName() { return "Incompatible"; }
            public List<ToolProvider> createToolProviders(TenantConfig c) { return List.of(); }
            public boolean supportsenant(TenantConfig c) { return false; } // never compatible
        };

        pluginRegistry.register(incompatible);

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("t1")
                .active(true)
                .enabledPlugins(Set.of("incompatible"))
                .build();

        ToolRegistry toolRegistry = pluginRegistry.buildToolRegistry(tenant, List.of());
        assertFalse(toolRegistry.getRegisteredNamespaces().contains("incompatible"));
    }

    @Test
    void should_include_all_core_providers_regardless_of_tenant() {
        TenantConfig tenant = TenantConfig.builder()
                .tenantId("t1")
                .active(true)
                .enabledPlugins(Set.of()) // no plugins enabled
                .build();

        ToolProvider core1 = createToolProvider("source_control");
        ToolProvider core2 = createToolProvider("issue_tracker");
        ToolRegistry toolRegistry = pluginRegistry.buildToolRegistry(tenant, List.of(core1, core2));

        assertTrue(toolRegistry.getRegisteredNamespaces().contains("source_control"));
        assertTrue(toolRegistry.getRegisteredNamespaces().contains("issue_tracker"));
    }

    // --- Helpers ---

    private WorkflowPlugin createPlugin(String namespace, String displayName) {
        return new WorkflowPlugin() {
            public String namespace() { return namespace; }
            public String displayName() { return displayName; }
            public List<ToolProvider> createToolProviders(TenantConfig config) {
                return List.of(createToolProvider(namespace));
            }
        };
    }

    private ToolProvider createToolProvider(String namespace) {
        return new ToolProvider() {
            public String namespace() { return namespace; }
            public List<Tool> toolDefinitions() {
                return List.of(Tool.of(namespace + "_get", "Get", Schema.object()));
            }
            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
    }
}
