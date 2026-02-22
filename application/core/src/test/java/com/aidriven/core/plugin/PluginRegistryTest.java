package com.aidriven.core.plugin;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.tenant.TenantConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {

    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = PluginRegistry.getInstance();
        registry.clear();
    }

    @Test
    void register_global_plugin_applies_to_all_tenants() {
        WorkflowPlugin globalPlugin = new WorkflowPlugin() {
            public String pluginId() { return "global-plugin"; }
            // tenantId() returns null by default → global
        };

        registry.register(globalPlugin);

        List<WorkflowPlugin> forAcme = registry.getPluginsForTenant("acme");
        List<WorkflowPlugin> forBeta = registry.getPluginsForTenant("beta");
        List<WorkflowPlugin> forNull = registry.getPluginsForTenant(null);

        assertEquals(1, forAcme.size());
        assertEquals(1, forBeta.size());
        assertEquals(1, forNull.size());
    }

    @Test
    void register_tenant_plugin_applies_only_to_that_tenant() {
        WorkflowPlugin acmePlugin = new WorkflowPlugin() {
            public String pluginId() { return "acme-plugin"; }
            public String tenantId() { return "acme"; }
        };

        registry.register(acmePlugin);

        assertEquals(1, registry.getPluginsForTenant("acme").size());
        assertEquals(0, registry.getPluginsForTenant("beta").size());
        assertEquals(0, registry.getPluginsForTenant(null).size());
    }

    @Test
    void executeOnTicketReceived_invokes_all_applicable_plugins() {
        boolean[] called = {false};

        WorkflowPlugin plugin = new WorkflowPlugin() {
            public String pluginId() { return "test"; }
            public TicketInfo onTicketReceived(TicketInfo ticket, TenantConfig config) {
                called[0] = true;
                return ticket;
            }
        };

        registry.register(plugin);

        TicketInfo ticket = TicketInfo.builder().ticketKey("TEST-1").build();
        TenantConfig tenantConfig = TenantConfig.builder().tenantId("default").tenantName("Default").build();

        registry.executeOnTicketReceived(ticket, tenantConfig);
        assertTrue(called[0]);
    }

    @Test
    void executeOnTicketReceived_plugin_exception_does_not_propagate() {
        WorkflowPlugin faultyPlugin = new WorkflowPlugin() {
            public String pluginId() { return "faulty"; }
            public TicketInfo onTicketReceived(TicketInfo ticket, TenantConfig config) {
                throw new RuntimeException("Plugin error");
            }
        };

        registry.register(faultyPlugin);

        TicketInfo ticket = TicketInfo.builder().ticketKey("TEST-1").build();
        // Should not throw
        assertDoesNotThrow(() -> registry.executeOnTicketReceived(ticket, null));
    }

    @Test
    void size_counts_all_plugins() {
        assertEquals(0, registry.size());

        registry.register(new WorkflowPlugin() {
            public String pluginId() { return "g1"; }
        });
        registry.register(new WorkflowPlugin() {
            public String pluginId() { return "t1"; }
            public String tenantId() { return "acme"; }
        });

        assertEquals(2, registry.size());
    }

    @Test
    void global_and_tenant_plugins_both_returned_for_tenant() {
        registry.register(new WorkflowPlugin() {
            public String pluginId() { return "global"; }
        });
        registry.register(new WorkflowPlugin() {
            public String pluginId() { return "acme-specific"; }
            public String tenantId() { return "acme"; }
        });

        List<WorkflowPlugin> plugins = registry.getPluginsForTenant("acme");
        assertEquals(2, plugins.size());
    }
}
