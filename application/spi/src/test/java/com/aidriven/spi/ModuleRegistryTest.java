package com.aidriven.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRegistryTest {

    private ModuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModuleRegistry();
    }

    // --- Registration ---

    @Test
    void should_register_module() {
        registry.register(stubModule("jira"));

        assertTrue(registry.isRegistered("jira"));
        assertEquals(Set.of("jira"), registry.getRegisteredModuleIds());
    }

    @Test
    void should_reject_duplicate_module_id() {
        registry.register(stubModule("jira"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(stubModule("jira")));
    }

    @Test
    void should_reject_null_module() {
        assertThrows(NullPointerException.class,
                () -> registry.register(null));
    }

    @Test
    void should_register_multiple_modules() {
        registry.register(stubModule("jira"));
        registry.register(stubModule("github"));
        registry.register(stubModule("claude"));

        assertEquals(3, registry.getRegisteredModuleIds().size());
    }

    // --- Initialization ---

    @Test
    void should_initialize_module() throws Exception {
        StubModule module = stubModule("jira");
        registry.register(module);

        registry.initializeModule("jira", Map.of("url", "https://jira.example.com"));

        assertTrue(registry.isInitialized("jira"));
        assertTrue(module.initCalled);
        assertEquals("https://jira.example.com", module.receivedConfig.get("url"));
    }

    @Test
    void should_fail_initialization_for_unknown_module() {
        assertThrows(ModuleInitializationException.class,
                () -> registry.initializeModule("unknown", Map.of()));
    }

    @Test
    void should_fail_when_dependency_not_initialized() {
        StubModule dependent = stubModule("tool-provider", List.of("claude"));
        registry.register(stubModule("claude"));
        registry.register(dependent);

        assertThrows(ModuleInitializationException.class,
                () -> registry.initializeModule("tool-provider", Map.of()));
    }

    @Test
    void should_initialize_all_in_dependency_order() throws Exception {
        StubModule core = stubModule("core");
        StubModule jira = stubModule("jira", List.of("core"));
        StubModule agent = stubModule("agent", List.of("core", "jira"));

        registry.register(core);
        registry.register(jira);
        registry.register(agent);

        registry.initializeAll(Map.of(
                "core", Map.of(),
                "jira", Map.of("url", "https://jira.test"),
                "agent", Map.of()));

        assertTrue(registry.isInitialized("core"));
        assertTrue(registry.isInitialized("jira"));
        assertTrue(registry.isInitialized("agent"));
    }

    // --- Retrieval ---

    @Test
    void should_get_module_by_id() {
        registry.register(stubModule("jira"));

        Optional<ServiceModule> module = registry.getModule("jira");

        assertTrue(module.isPresent());
        assertEquals("jira", module.get().moduleId());
    }

    @Test
    void should_return_empty_for_unknown_module() {
        Optional<ServiceModule> module = registry.getModule("unknown");

        assertTrue(module.isEmpty());
    }

    @Test
    void should_get_typed_module() {
        StubModule module = stubModule("jira");
        registry.register(module);

        StubModule retrieved = registry.getModule("jira", StubModule.class);

        assertSame(module, retrieved);
    }

    @Test
    void should_throw_for_wrong_type() {
        registry.register(stubModule("jira"));

        assertThrows(ClassCastException.class,
                () -> registry.getModule("jira", AiEngineModule.class));
    }

    @Test
    void should_throw_for_missing_typed_module() {
        assertThrows(NoSuchElementException.class,
                () -> registry.getModule("missing", StubModule.class));
    }

    // --- Health Check ---

    @Test
    void should_report_health_of_initialized_modules() throws Exception {
        registry.register(stubModule("jira"));
        registry.register(stubModule("github"));
        registry.initializeModule("jira", Map.of());
        registry.initializeModule("github", Map.of());

        Map<String, Boolean> health = registry.healthCheck();

        assertEquals(2, health.size());
        assertTrue(health.get("jira"));
        assertTrue(health.get("github"));
    }

    @Test
    void should_return_empty_health_when_nothing_initialized() {
        registry.register(stubModule("jira"));

        Map<String, Boolean> health = registry.healthCheck();

        assertTrue(health.isEmpty());
    }

    // --- Shutdown ---

    @Test
    void should_shutdown_all_modules() throws Exception {
        StubModule jira = stubModule("jira");
        StubModule github = stubModule("github");
        registry.register(jira);
        registry.register(github);
        registry.initializeModule("jira", Map.of());
        registry.initializeModule("github", Map.of());

        registry.shutdownAll();

        assertTrue(jira.shutdownCalled);
        assertTrue(github.shutdownCalled);
        assertFalse(registry.isInitialized("jira"));
        assertFalse(registry.isInitialized("github"));
    }

    // --- Topological Sort ---

    @Test
    void should_sort_modules_by_dependencies() {
        registry.register(stubModule("agent", List.of("core", "jira")));
        registry.register(stubModule("core"));
        registry.register(stubModule("jira", List.of("core")));

        List<String> order = registry.topologicalSort();

        assertTrue(order.indexOf("core") < order.indexOf("jira"));
        assertTrue(order.indexOf("jira") < order.indexOf("agent"));
        assertTrue(order.indexOf("core") < order.indexOf("agent"));
    }

    @Test
    void should_handle_modules_with_no_dependencies() {
        registry.register(stubModule("a"));
        registry.register(stubModule("b"));
        registry.register(stubModule("c"));

        List<String> order = registry.topologicalSort();

        assertEquals(3, order.size());
    }

    // --- Helpers ---

    private StubModule stubModule(String id) {
        return new StubModule(id, List.of());
    }

    private StubModule stubModule(String id, List<String> deps) {
        return new StubModule(id, deps);
    }

    static class StubModule implements ServiceModule {
        private final String id;
        private final List<String> deps;
        boolean initCalled = false;
        boolean shutdownCalled = false;
        Map<String, String> receivedConfig;

        StubModule(String id, List<String> deps) {
            this.id = id;
            this.deps = deps;
        }

        @Override
        public String moduleId() {
            return id;
        }

        @Override
        public String displayName() {
            return "Stub: " + id;
        }

        @Override
        public List<String> dependencies() {
            return deps;
        }

        @Override
        public void initialize(Map<String, String> config) {
            this.initCalled = true;
            this.receivedConfig = config;
        }

        @Override
        public void shutdown() {
            this.shutdownCalled = true;
        }
    }
}
