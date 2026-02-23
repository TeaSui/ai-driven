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

    @Test
    void register_and_retrieve_plugin() {
        WorkflowPlugin plugin = stubPlugin("monitoring", true);
        registry.register(plugin);

        assertTrue(registry.getPlugin("monitoring").isPresent());
        assertEquals(1, registry.size());
    }

    @Test
    void register_duplicate_throws() {
        registry.register(stubPlugin("monitoring", true));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(stubPlugin("monitoring", true)));
    }

    @Test
    void registerOrReplace_replaces_existing() {
        registry.register(stubPlugin("monitoring", true));
        registry.registerOrReplace(stubPlugin("monitoring", false));
        assertEquals(1, registry.size());
    }

    @Test
    void getEnabledPlugins_filters_by_tenant() {
        registry.register(stubPlugin("monitoring", true));
        registry.register(stubPlugin("messaging", false));

        TenantConfig tenant = TenantConfig.builder().tenantId("t1").active(true).build();
        List<WorkflowPlugin> enabled = registry.getEnabledPlugins(tenant);

        assertEquals(1, enabled.size());
        assertEquals("monitoring", enabled.get(0).pluginId());
    }

    @Test
    void initializeForTenant_initializes_enabled_plugins() {
        TrackingPlugin plugin = new TrackingPlugin("p1", true);
        registry.register(plugin);

        TenantConfig tenant = TenantConfig.builder().tenantId("t1").active(true).build();
        List<WorkflowPlugin> initialized = registry.initializeForTenant(tenant);

        assertEquals(1, initialized.size());
        assertTrue(plugin.initialized);
    }

    @Test
    void initializeForTenant_is_idempotent() {
        TrackingPlugin plugin = new TrackingPlugin("p1", true);
        registry.register(plugin);

        TenantConfig tenant = TenantConfig.builder().tenantId("t1").active(true).build();
        registry.initializeForTenant(tenant);
        registry.initializeForTenant(tenant);

        assertEquals(1, plugin.initCount);
    }

    @Test
    void initializeForTenant_continues_on_failure() {
        registry.register(new FailingPlugin("fail"));
        registry.register(stubPlugin("ok", true));

        TenantConfig tenant = TenantConfig.builder().tenantId("t1").active(true).build();
        List<WorkflowPlugin> initialized = registry.initializeForTenant(tenant);

        // Only the non-failing plugin should be in the initialized list
        assertEquals(1, initialized.size());
        assertEquals("ok", initialized.get(0).pluginId());
    }

    @Test
    void getRegisteredPluginIds_returns_all_ids() {
        registry.register(stubPlugin("p1", true));
        registry.register(stubPlugin("p2", false));
        assertEquals(Set.of("p1", "p2"), registry.getRegisteredPluginIds());
    }

    @Test
    void shutdownForTenant_calls_shutdown_on_initialized_plugins() {
        TrackingPlugin plugin = new TrackingPlugin("p1", true);
        registry.register(plugin);

        TenantConfig tenant = TenantConfig.builder().tenantId("t1").active(true).build();
        registry.initializeForTenant(tenant);
        registry.shutdownForTenant("t1");

        assertTrue(plugin.shutdown);
    }

    // --- Helpers ---

    private WorkflowPlugin stubPlugin(String id, boolean enabled) {
        return new WorkflowPlugin() {
            public String pluginId() { return id; }
            public String displayName() { return id; }
            public boolean isEnabled(TenantConfig t) { return enabled; }
            public void initialize(TenantConfig t) {}
        };
    }

    static class TrackingPlugin implements WorkflowPlugin {
        final String id;
        final boolean enabled;
        boolean initialized = false;
        boolean shutdown = false;
        int initCount = 0;

        TrackingPlugin(String id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }

        public String pluginId() { return id; }
        public String displayName() { return id; }
        public boolean isEnabled(TenantConfig t) { return enabled; }
        public void initialize(TenantConfig t) { initialized = true; initCount++; }
        public void shutdown() { shutdown = true; }
    }

    static class FailingPlugin implements WorkflowPlugin {
        final String id;
        FailingPlugin(String id) { this.id = id; }
        public String pluginId() { return id; }
        public String displayName() { return id; }
        public boolean isEnabled(TenantConfig t) { return true; }
        public void initialize(TenantConfig t) {
            throw new PluginInitializationException("Intentional failure");
        }
    }
}
