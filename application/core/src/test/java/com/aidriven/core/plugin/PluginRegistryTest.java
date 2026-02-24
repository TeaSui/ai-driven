package com.aidriven.core.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {

    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
    }

    @Test
    void register_and_find_plugin() {
        PluginDescriptor descriptor = PluginDescriptor.of(
                "monitoring", "Monitoring Plugin", "Provides monitoring tools");

        registry.register(descriptor);

        Optional<PluginDescriptor> found = registry.find("monitoring");
        assertTrue(found.isPresent());
        assertEquals("Monitoring Plugin", found.get().displayName());
    }

    @Test
    void find_returns_empty_for_unknown_plugin() {
        assertTrue(registry.find("unknown").isEmpty());
    }

    @Test
    void find_returns_empty_for_null_namespace() {
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void register_throws_for_blank_namespace() {
        PluginDescriptor invalid = new PluginDescriptor(
                "", "Name", "Desc", "1.0", List.of(), Map.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> registry.register(invalid));
    }

    @Test
    void getEnabledPlugins_filters_by_namespace() {
        registry.register(PluginDescriptor.of("monitoring", "Monitoring", "Monitor"));
        registry.register(PluginDescriptor.of("messaging", "Messaging", "Message"));
        registry.register(PluginDescriptor.of("data", "Data", "Data access"));

        List<PluginDescriptor> enabled = registry.getEnabledPlugins(Set.of("monitoring", "data"));

        assertEquals(2, enabled.size());
        assertTrue(enabled.stream().anyMatch(p -> p.namespace().equals("monitoring")));
        assertTrue(enabled.stream().anyMatch(p -> p.namespace().equals("data")));
        assertFalse(enabled.stream().anyMatch(p -> p.namespace().equals("messaging")));
    }

    @Test
    void getEnabledPlugins_returns_empty_for_null_set() {
        registry.register(PluginDescriptor.of("monitoring", "Monitoring", "Monitor"));
        assertTrue(registry.getEnabledPlugins(null).isEmpty());
    }

    @Test
    void getEnabledPlugins_returns_empty_for_empty_set() {
        registry.register(PluginDescriptor.of("monitoring", "Monitoring", "Monitor"));
        assertTrue(registry.getEnabledPlugins(Set.of()).isEmpty());
    }

    @Test
    void size_reflects_registered_plugins() {
        assertEquals(0, registry.size());
        registry.register(PluginDescriptor.of("p1", "P1", "Plugin 1"));
        registry.register(PluginDescriptor.of("p2", "P2", "Plugin 2"));
        assertEquals(2, registry.size());
    }

    @Test
    void isRegistered_returns_correct_value() {
        registry.register(PluginDescriptor.of("monitoring", "Monitoring", "Monitor"));
        assertTrue(registry.isRegistered("monitoring"));
        assertFalse(registry.isRegistered("messaging"));
        assertFalse(registry.isRegistered(null));
    }

    @Test
    void getNamespaces_returns_all_registered() {
        registry.register(PluginDescriptor.of("p1", "P1", "Plugin 1"));
        registry.register(PluginDescriptor.of("p2", "P2", "Plugin 2"));
        assertTrue(registry.getNamespaces().containsAll(Set.of("p1", "p2")));
    }
}
