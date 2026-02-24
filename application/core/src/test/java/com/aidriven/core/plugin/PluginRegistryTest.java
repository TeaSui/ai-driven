package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {

    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
    }

    private WorkflowPlugin stubPlugin(String id, boolean enabled) {
        return new WorkflowPlugin() {
            @Override
            public String pluginId() { return id; }

            @Override
            public String description() { return "Stub plugin: " + id; }

            @Override
            public void initialize(TenantConfig tenantConfig) { /* no-op */ }

            @Override
            public boolean isEnabled(TenantConfig tenantConfig) { return enabled; }
        };
    }

    @Test
    void register_and_retrieve_plugin() {
        registry.register(stubPlugin("monitoring", true));
        assertTrue(registry.isRegistered("monitoring"));
        assertTrue(registry.getPlugin("monitoring").isPresent());
    }

    @Test
    void register_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void register_blank_pluginId_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(stubPlugin("", true)));
    }

    @Test
    void getEnabledPlugins_returns_only_enabled_for_tenant() {
        registry.register(stubPlugin("monitoring", true));
        registry.register(stubPlugin("messaging", true));
        registry.register(stubPlugin("data", false));

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("acme")
                .enabledPlugins(Set.of("monitoring", "messaging", "data"))
                .build();

        List<WorkflowPlugin> enabled = registry.getEnabledPlugins(tenant);
        assertEquals(2, enabled.size());
        assertTrue(enabled.stream().anyMatch(p -> p.pluginId().equals("monitoring")));
        assertTrue(enabled.stream().anyMatch(p -> p.pluginId().equals("messaging")));
    }

    @Test
    void getEnabledPlugins_only_returns_plugins_in_tenant_config() {
        registry.register(stubPlugin("monitoring", true));
        registry.register(stubPlugin("messaging", true));

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("acme")
                .enabledPlugins(Set.of("monitoring")) // only monitoring
                .build();

        List<WorkflowPlugin> enabled = registry.getEnabledPlugins(tenant);
        assertEquals(1, enabled.size());
        assertEquals("monitoring", enabled.get(0).pluginId());
    }

    @Test
    void getEnabledPlugins_null_tenant_returns_empty() {
        registry.register(stubPlugin("monitoring", true));
        assertTrue(registry.getEnabledPlugins(null).isEmpty());
    }

    @Test
    void getEnabledPlugins_null_enabledPlugins_returns_empty() {
        registry.register(stubPlugin("monitoring", true));
        TenantConfig tenant = TenantConfig.builder().tenantId("acme").enabledPlugins(null).build();
        assertTrue(registry.getEnabledPlugins(tenant).isEmpty());
    }

    @Test
    void getPlugin_unknown_returns_empty() {
        assertTrue(registry.getPlugin("unknown").isEmpty());
    }

    @Test
    void size_returns_correct_count() {
        assertEquals(0, registry.size());
        registry.register(stubPlugin("p1", true));
        registry.register(stubPlugin("p2", true));
        assertEquals(2, registry.size());
    }

    @Test
    void initializeForTenant_calls_initialize_on_enabled_plugins() {
        boolean[] initialized = {false};
        WorkflowPlugin plugin = new WorkflowPlugin() {
            @Override public String pluginId() { return "test"; }
            @Override public String description() { return "Test"; }
            @Override public void initialize(TenantConfig t) { initialized[0] = true; }
            @Override public boolean isEnabled(TenantConfig t) { return true; }
        };
        registry.register(plugin);

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("t1")
                .enabledPlugins(Set.of("test"))
                .build();
        registry.initializeForTenant(tenant);

        assertTrue(initialized[0]);
    }

    @Test
    void initializeForTenant_continues_on_plugin_error() {
        WorkflowPlugin failingPlugin = new WorkflowPlugin() {
            @Override public String pluginId() { return "failing"; }
            @Override public String description() { return "Failing"; }
            @Override public void initialize(TenantConfig t) throws Exception { throw new Exception("Init failed"); }
            @Override public boolean isEnabled(TenantConfig t) { return true; }
        };
        registry.register(failingPlugin);

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("t1")
                .enabledPlugins(Set.of("failing"))
                .build();

        // Should not throw
        assertDoesNotThrow(() -> registry.initializeForTenant(tenant));
    }
}
