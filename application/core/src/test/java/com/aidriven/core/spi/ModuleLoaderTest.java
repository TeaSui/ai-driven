package com.aidriven.core.spi;

import com.aidriven.core.source.SourceControlClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ModuleLoaderTest {

    private ServiceProviderRegistry registry;
    private ModuleLoader loader;

    @BeforeEach
    void setUp() {
        registry = new ServiceProviderRegistry();
        loader = new ModuleLoader(registry);
    }

    @Test
    void should_initialize_module_with_valid_config() {
        ModuleDescriptor module = new TestModule("test-mod", List.of("KEY_A"));

        int count = loader.initializeModules(
                List.of(module),
                Map.of("KEY_A", "value-a"));

        assertEquals(1, count);
        assertEquals(1, loader.getLoadedModules().size());
        assertEquals("test-mod", loader.getLoadedModules().get(0).name());
    }

    @Test
    void should_skip_module_with_missing_config() {
        ModuleDescriptor module = new TestModule("needs-config", List.of("MISSING_KEY"));

        int count = loader.initializeModules(
                List.of(module),
                Map.of("OTHER_KEY", "value"));

        assertEquals(0, count);
        assertTrue(loader.getLoadedModules().isEmpty());
    }

    @Test
    void should_skip_module_with_blank_config_value() {
        ModuleDescriptor module = new TestModule("blank-config", List.of("KEY_A"));

        int count = loader.initializeModules(
                List.of(module),
                Map.of("KEY_A", "  "));

        assertEquals(0, count);
    }

    @Test
    void should_initialize_modules_in_priority_order() {
        List<String> initOrder = new java.util.ArrayList<>();

        ModuleDescriptor lowPriority = new TestModule("low", List.of()) {
            @Override
            public int priority() { return 200; }
            @Override
            public void initialize(ServiceProviderRegistry r, Map<String, String> c) {
                initOrder.add("low");
            }
        };

        ModuleDescriptor highPriority = new TestModule("high", List.of()) {
            @Override
            public int priority() { return 10; }
            @Override
            public void initialize(ServiceProviderRegistry r, Map<String, String> c) {
                initOrder.add("high");
            }
        };

        loader.initializeModules(List.of(lowPriority, highPriority), Map.of());

        assertEquals(List.of("high", "low"), initOrder);
    }

    @Test
    void should_continue_when_module_throws_during_init() {
        ModuleDescriptor failing = new TestModule("failing", List.of()) {
            @Override
            public void initialize(ServiceProviderRegistry r, Map<String, String> c) {
                throw new RuntimeException("Init failed!");
            }
        };
        ModuleDescriptor working = new TestModule("working", List.of());

        int count = loader.initializeModules(List.of(failing, working), Map.of());

        assertEquals(1, count);
        assertEquals(1, loader.getLoadedModules().size());
        assertEquals("working", loader.getLoadedModules().get(0).name());
    }

    @Test
    void should_merge_optional_defaults_with_config() {
        java.util.concurrent.atomic.AtomicReference<String> capturedValue = new java.util.concurrent.atomic.AtomicReference<>();

        ModuleDescriptor module = new TestModule("defaults", List.of()) {
            @Override
            public Map<String, String> optionalConfigDefaults() {
                return Map.of("OPT_KEY", "default-value");
            }
            @Override
            public void initialize(ServiceProviderRegistry r, Map<String, String> c) {
                capturedValue.set(c.get("OPT_KEY"));
            }
        };

        loader.initializeModules(List.of(module), Map.of());
        assertEquals("default-value", capturedValue.get());
    }

    @Test
    void should_override_defaults_with_provided_config() {
        java.util.concurrent.atomic.AtomicReference<String> capturedValue = new java.util.concurrent.atomic.AtomicReference<>();

        ModuleDescriptor module = new TestModule("defaults", List.of()) {
            @Override
            public Map<String, String> optionalConfigDefaults() {
                return Map.of("OPT_KEY", "default-value");
            }
            @Override
            public void initialize(ServiceProviderRegistry r, Map<String, String> c) {
                capturedValue.set(c.get("OPT_KEY"));
            }
        };

        loader.initializeModules(List.of(module), Map.of("OPT_KEY", "custom-value"));
        assertEquals("custom-value", capturedValue.get());
    }

    @Test
    void should_shutdown_modules_in_reverse_order() {
        List<String> shutdownOrder = new java.util.ArrayList<>();

        ModuleDescriptor first = new TestModule("first", List.of()) {
            @Override
            public int priority() { return 10; }
            @Override
            public void shutdown() { shutdownOrder.add("first"); }
        };

        ModuleDescriptor second = new TestModule("second", List.of()) {
            @Override
            public int priority() { return 20; }
            @Override
            public void shutdown() { shutdownOrder.add("second"); }
        };

        loader.initializeModules(List.of(first, second), Map.of());
        loader.shutdownAll();

        assertEquals(List.of("second", "first"), shutdownOrder);
        assertTrue(loader.getLoadedModules().isEmpty());
    }

    @Test
    void should_handle_shutdown_errors_gracefully() {
        ModuleDescriptor failing = new TestModule("failing-shutdown", List.of()) {
            @Override
            public void shutdown() { throw new RuntimeException("Shutdown error"); }
        };

        loader.initializeModules(List.of(failing), Map.of());
        assertDoesNotThrow(() -> loader.shutdownAll());
    }

    // --- Test helper ---

    private static class TestModule implements ModuleDescriptor {
        private final String name;
        private final List<String> requiredKeys;

        TestModule(String name, List<String> requiredKeys) {
            this.name = name;
            this.requiredKeys = requiredKeys;
        }

        @Override public String name() { return name; }
        @Override public String version() { return "1.0.0"; }
        @Override public List<Class<?>> providedServices() { return List.of(SourceControlClient.class); }
        @Override public List<String> requiredConfigKeys() { return requiredKeys; }

        @Override
        public void initialize(ServiceProviderRegistry registry, Map<String, String> config) {
            registry.register(SourceControlClient.class, name, mock(SourceControlClient.class));
        }
    }
}
