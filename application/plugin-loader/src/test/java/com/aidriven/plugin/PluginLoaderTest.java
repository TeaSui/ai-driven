package com.aidriven.plugin;

import com.aidriven.contracts.plugin.PluginCapability;
import com.aidriven.contracts.plugin.PluginDescriptor;
import com.aidriven.contracts.plugin.PluginRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginLoaderTest {

    private DefaultPluginRegistry registry;
    private PluginLoader loader;

    @BeforeEach
    void setUp() {
        registry = new DefaultPluginRegistry();
        loader = new PluginLoader(registry);
    }

    @Test
    void should_create_loader_with_registry() {
        assertNotNull(loader.getRegistry());
        assertSame(registry, loader.getRegistry());
    }

    @Test
    void should_throw_on_null_registry() {
        assertThrows(NullPointerException.class, () -> new PluginLoader(null));
    }

    @Test
    void should_return_zero_when_no_plugins_on_classpath() {
        // No META-INF/services file exists in test classpath
        int loaded = loader.loadPlugins();

        assertEquals(0, loaded);
        assertTrue(loader.getLoadedPlugins().isEmpty());
    }

    @Test
    void should_return_unmodifiable_loaded_plugins_list() {
        List<PluginDescriptor> plugins = loader.getLoadedPlugins();

        assertThrows(UnsupportedOperationException.class,
                () -> plugins.add(createMockPlugin()));
    }

    @Test
    void should_shutdown_clears_loaded_plugins() {
        // Even with no plugins, shutdown should work
        loader.shutdown();
        assertTrue(loader.getLoadedPlugins().isEmpty());
    }

    private PluginDescriptor createMockPlugin() {
        return new PluginDescriptor() {
            public String id() { return "test-plugin"; }
            public String name() { return "Test Plugin"; }
            public String version() { return "1.0.0"; }
            public List<PluginCapability> capabilities() { return List.of(PluginCapability.TOOL_PROVIDER); }
            public void initialize(PluginRegistry registry) { }
        };
    }
}
