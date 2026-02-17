package com.aidriven.platform;

import com.aidriven.core.agent.tool.Tool;
import com.aidriven.core.agent.tool.ToolCall;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.agent.tool.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowComposerTest {

    private ModuleRegistry moduleRegistry;
    private WorkflowComposer composer;

    @BeforeEach
    void setUp() {
        moduleRegistry = new ModuleRegistry();
        composer = new WorkflowComposer(moduleRegistry);
    }

    @Test
    void should_compose_registry_with_enabled_modules_only() {
        // Register two factories
        composer.registerToolProviderFactory(new WorkflowComposer.ToolProviderFactory() {
            public String moduleId() { return "source_control"; }
            public ToolProvider create(TenantContext tenant) {
                return new StubToolProvider("source_control");
            }
        });
        composer.registerToolProviderFactory(new WorkflowComposer.ToolProviderFactory() {
            public String moduleId() { return "monitoring"; }
            public ToolProvider create(TenantContext tenant) {
                return new StubToolProvider("monitoring");
            }
        });

        TenantContext tenant = TenantContext.of("t1", "Test", Map.of());

        // Only enable source_control
        ToolRegistry registry = composer.composeToolRegistry(tenant, List.of("source_control"));

        assertTrue(registry.getRegisteredNamespaces().contains("source_control"));
        assertFalse(registry.getRegisteredNamespaces().contains("monitoring"));
    }

    @Test
    void should_compose_empty_registry_when_no_modules_enabled() {
        TenantContext tenant = TenantContext.of("t1", "Test", Map.of());

        ToolRegistry registry = composer.composeToolRegistry(tenant, List.of());

        assertTrue(registry.getRegisteredNamespaces().isEmpty());
    }

    @Test
    void should_handle_factory_exception_gracefully() {
        composer.registerToolProviderFactory(new WorkflowComposer.ToolProviderFactory() {
            public String moduleId() { return "broken"; }
            public ToolProvider create(TenantContext tenant) {
                throw new RuntimeException("Connection failed");
            }
        });
        composer.registerToolProviderFactory(new WorkflowComposer.ToolProviderFactory() {
            public String moduleId() { return "working"; }
            public ToolProvider create(TenantContext tenant) {
                return new StubToolProvider("working");
            }
        });

        TenantContext tenant = TenantContext.of("t1", "Test", Map.of());

        // Should not throw, and should still register the working module
        ToolRegistry registry = composer.composeToolRegistry(tenant, List.of("broken", "working"));

        assertTrue(registry.getRegisteredNamespaces().contains("working"));
        assertFalse(registry.getRegisteredNamespaces().contains("broken"));
    }

    @Test
    void should_skip_null_provider_from_factory() {
        composer.registerToolProviderFactory(new WorkflowComposer.ToolProviderFactory() {
            public String moduleId() { return "nullable"; }
            public ToolProvider create(TenantContext tenant) {
                return null;
            }
        });

        TenantContext tenant = TenantContext.of("t1", "Test", Map.of());
        ToolRegistry registry = composer.composeToolRegistry(tenant, List.of("nullable"));

        assertTrue(registry.getRegisteredNamespaces().isEmpty());
    }

    // Stub ToolProvider for testing
    private static class StubToolProvider implements ToolProvider {
        private final String ns;

        StubToolProvider(String ns) {
            this.ns = ns;
        }

        @Override
        public String namespace() { return ns; }

        @Override
        public List<Tool> toolDefinitions() {
            return List.of(Tool.of(ns + "_test", "Test tool", Schema.object()));
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(), "ok");
        }
    }
}
