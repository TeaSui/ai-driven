package com.aidriven.core.plugin;

import com.aidriven.core.tenant.TenantConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {

    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
    }

    @Test
    void register_and_retrieve_plugin() {
        WorkflowPlugin plugin = stubPlugin("slack", "Slack Notifications");
        registry.register(plugin);

        assertTrue(registry.getPlugin("slack").isPresent());
        assertEquals("Slack Notifications", registry.getPlugin("slack").get().displayName());
    }

    @Test
    void register_throws_for_null_plugin() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void register_throws_for_blank_plugin_id() {
        WorkflowPlugin plugin = stubPlugin("", "Empty ID");
        assertThrows(IllegalArgumentException.class, () -> registry.register(plugin));
    }

    @Test
    void getActivePlugins_returns_only_enabled_plugins() {
        registry.register(stubPlugin("slack", "Slack"));
        registry.register(stubPlugin("sonar", "SonarQube"));

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .enabledPlugins(Set.of("slack")) // only slack enabled
                .build();

        var active = registry.getActivePlugins(tenant);
        assertEquals(1, active.size());
        assertEquals("slack", active.get(0).pluginId());
    }

    @Test
    void getActivePlugins_returns_empty_for_null_tenant() {
        registry.register(stubPlugin("slack", "Slack"));
        assertTrue(registry.getActivePlugins(null).isEmpty());
    }

    @Test
    void getActivePlugins_returns_empty_when_no_plugins_enabled() {
        registry.register(stubPlugin("slack", "Slack"));

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .enabledPlugins(Set.of()) // none enabled
                .build();

        assertTrue(registry.getActivePlugins(tenant).isEmpty());
    }

    @Test
    void initializeForTenant_calls_initialize_on_active_plugins() {
        boolean[] initialized = {false};
        WorkflowPlugin plugin = new WorkflowPlugin() {
            public String pluginId() { return "test-plugin"; }
            public String displayName() { return "Test Plugin"; }
            public boolean isApplicable(TenantConfig t) { return true; }
            public void initialize(TenantConfig t, Map<String, String> conf) {
                initialized[0] = true;
            }
        };
        registry.register(plugin);

        TenantConfig tenant = TenantConfig.builder()
                .tenantId("acme")
                .tenantName("Acme")
                .enabledPlugins(Set.of("test-plugin"))
                .build();

        registry.initializeForTenant(tenant);
        assertTrue(initialized[0]);
    }

    @Test
    void getRegisteredPluginIds_returns_all_ids() {
        registry.register(stubPlugin("p1", "P1"));
        registry.register(stubPlugin("p2", "P2"));

        var ids = registry.getRegisteredPluginIds();
        assertTrue(ids.contains("p1"));
        assertTrue(ids.contains("p2"));
        assertEquals(2, ids.size());
    }

    @Test
    void size_reflects_registered_count() {
        assertEquals(0, registry.size());
        registry.register(stubPlugin("p1", "P1"));
        assertEquals(1, registry.size());
    }

    // --- Helper ---

    private WorkflowPlugin stubPlugin(String id, String name) {
        return new WorkflowPlugin() {
            public String pluginId() { return id; }
            public String displayName() { return name; }
            public boolean isApplicable(TenantConfig t) { return true; }
            public void initialize(TenantConfig t, Map<String, String> conf) {}
        };
    }
}
