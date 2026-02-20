package com.aidriven.spi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModuleDescriptorTest {

    /**
     * Example module descriptor for testing the contract.
     */
    static class TestModuleDescriptor implements ModuleDescriptor {

        @Override
        public String moduleId() {
            return "test-module";
        }

        @Override
        public String displayName() {
            return "Test Module";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public List<Class<?>> providedInterfaces() {
            return List.of(SourceControlProvider.class);
        }

        @Override
        public void register(ProviderRegistry registry, Map<String, String> config) {
            // No-op for test
        }
    }

    @Test
    void should_describe_module_correctly() {
        ModuleDescriptor descriptor = new TestModuleDescriptor();

        assertEquals("test-module", descriptor.moduleId());
        assertEquals("Test Module", descriptor.displayName());
        assertEquals("1.0.0", descriptor.version());
        assertEquals(1, descriptor.providedInterfaces().size());
        assertTrue(descriptor.providedInterfaces().contains(SourceControlProvider.class));
    }

    @Test
    void should_register_into_registry() {
        ProviderRegistry registry = new ProviderRegistry();
        ModuleDescriptor descriptor = new TestModuleDescriptor();

        // Should not throw
        assertDoesNotThrow(() -> descriptor.register(registry, Map.of()));
    }
}
