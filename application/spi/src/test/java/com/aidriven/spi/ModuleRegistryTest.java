package com.aidriven.spi;

import com.aidriven.spi.tenant.DefaultModuleContext;
import com.aidriven.spi.tenant.TenantConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRegistryTest {

    private ModuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModuleRegistry();
    }

    // --- Registration ---

    @Test
    void should_register_and_retrieve_module() {
        StubModule module = new StubModule("test-module", ModuleType.SOURCE_CONTROL);
        registry.register(module);

        Optional<AiDrivenModule> result = registry.get("test-module");
        assertTrue(result.isPresent());
        assertEquals("test-module", result.get().id());
    }

    @Test
    void should_throw_on_duplicate_registration() {
        StubModule module1 = new StubModule("dup", ModuleType.SOURCE_CONTROL);
        StubModule module2 = new StubModule("dup", ModuleType.SOURCE_CONTROL);

        registry.register(module1);
        assertThrows(IllegalStateException.class, () -> registry.register(module2));
    }

    @Test
    void should_throw_on_null_module() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void should_return_empty_for_unknown_id() {
        assertTrue(registry.get("nonexistent").isEmpty());
    }

    @Test
    void should_throw_on_getRequired_for_unknown_id() {
        assertThrows(NoSuchElementException.class, () -> registry.getRequired("nonexistent"));
    }

    // --- Type Index ---

    @Test
    void should_retrieve_modules_by_type() {
        registry.register(new StubModule("github", ModuleType.SOURCE_CONTROL));
        registry.register(new StubModule("bitbucket", ModuleType.SOURCE_CONTROL));
        registry.register(new StubModule("jira", ModuleType.ISSUE_TRACKER));

        List<AiDrivenModule> scModules = registry.getByType(ModuleType.SOURCE_CONTROL);
        assertEquals(2, scModules.size());

        List<AiDrivenModule> itModules = registry.getByType(ModuleType.ISSUE_TRACKER);
        assertEquals(1, itModules.size());
        assertEquals("jira", itModules.get(0).id());
    }

    @Test
    void should_return_empty_list_for_unregistered_type() {
        List<AiDrivenModule> result = registry.getByType(ModuleType.MONITORING);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_get_first_by_type() {
        registry.register(new StubModule("github", ModuleType.SOURCE_CONTROL));
        registry.register(new StubModule("bitbucket", ModuleType.SOURCE_CONTROL));

        Optional<AiDrivenModule> first = registry.getFirstByType(ModuleType.SOURCE_CONTROL);
        assertTrue(first.isPresent());
        assertEquals("github", first.get().id());
    }

    // --- Typed Get ---

    @Test
    void should_get_module_with_type_cast() {
        StubModule module = new StubModule("test", ModuleType.SOURCE_CONTROL);
        registry.register(module);

        Optional<StubModule> result = registry.get("test", StubModule.class);
        assertTrue(result.isPresent());
    }

    @Test
    void should_return_empty_when_type_mismatch() {
        registry.register(new StubModule("test", ModuleType.SOURCE_CONTROL));

        // AnotherStubModule is a different class
        Optional<AnotherStubModule> result = registry.get("test", AnotherStubModule.class);
        assertTrue(result.isEmpty());
    }

    // --- Initialize All ---

    @Test
    void should_initialize_all_modules() {
        StubModule m1 = new StubModule("m1", ModuleType.SOURCE_CONTROL);
        StubModule m2 = new StubModule("m2", ModuleType.ISSUE_TRACKER);
        registry.register(m1);
        registry.register(m2);

        TenantConfig config = TenantConfig.builder("test-tenant").build();
        ModuleContext context = new DefaultModuleContext(config);

        Map<String, Exception> results = registry.initializeAll(context);

        assertEquals(2, results.size());
        assertNull(results.get("m1"));
        assertNull(results.get("m2"));
        assertTrue(m1.isReady());
        assertTrue(m2.isReady());
    }

    @Test
    void should_capture_initialization_failures() {
        FailingModule failing = new FailingModule("bad");
        StubModule good = new StubModule("good", ModuleType.SOURCE_CONTROL);
        registry.register(failing);
        registry.register(good);

        TenantConfig config = TenantConfig.builder("test-tenant").build();
        ModuleContext context = new DefaultModuleContext(config);

        Map<String, Exception> results = registry.initializeAll(context);

        assertNotNull(results.get("bad"));
        assertNull(results.get("good"));
        assertTrue(good.isReady());
        assertFalse(failing.isReady());
    }

    // --- Health Check ---

    @Test
    void should_health_check_all_modules() {
        StubModule m1 = new StubModule("m1", ModuleType.SOURCE_CONTROL);
        registry.register(m1);

        Map<String, HealthCheckResult> results = registry.healthCheckAll();

        assertEquals(1, results.size());
        assertTrue(results.get("m1").isHealthy());
    }

    // --- Unregister ---

    @Test
    void should_unregister_module() {
        registry.register(new StubModule("removable", ModuleType.SOURCE_CONTROL));
        assertTrue(registry.get("removable").isPresent());

        boolean removed = registry.unregister("removable");
        assertTrue(removed);
        assertTrue(registry.get("removable").isEmpty());
    }

    @Test
    void should_return_false_when_unregistering_unknown() {
        assertFalse(registry.unregister("nonexistent"));
    }

    // --- Shutdown ---

    @Test
    void should_shutdown_all_and_clear() {
        StubModule m1 = new StubModule("m1", ModuleType.SOURCE_CONTROL);
        registry.register(m1);
        assertEquals(1, registry.size());

        registry.shutdownAll();

        assertEquals(0, registry.size());
        assertTrue(registry.getRegisteredIds().isEmpty());
    }

    // --- Size & IDs ---

    @Test
    void should_return_correct_size() {
        assertEquals(0, registry.size());
        registry.register(new StubModule("a", ModuleType.SOURCE_CONTROL));
        registry.register(new StubModule("b", ModuleType.ISSUE_TRACKER));
        assertEquals(2, registry.size());
    }

    @Test
    void should_return_registered_ids() {
        registry.register(new StubModule("alpha", ModuleType.SOURCE_CONTROL));
        registry.register(new StubModule("beta", ModuleType.ISSUE_TRACKER));

        Set<String> ids = registry.getRegisteredIds();
        assertEquals(Set.of("alpha", "beta"), ids);
    }

    // --- Stub Implementations ---

    static class StubModule implements AiDrivenModule {
        private final String id;
        private final ModuleType type;
        private boolean ready = false;

        StubModule(String id, ModuleType type) {
            this.id = id;
            this.type = type;
        }

        @Override public String id() { return id; }
        @Override public ModuleType type() { return type; }
        @Override public void initialize(ModuleContext context) { ready = true; }
        @Override public HealthCheckResult healthCheck() { return HealthCheckResult.healthy(); }
        @Override public boolean isReady() { return ready; }
    }

    static class AnotherStubModule implements AiDrivenModule {
        @Override public String id() { return "another"; }
        @Override public ModuleType type() { return ModuleType.EXTENSION; }
        @Override public void initialize(ModuleContext context) {}
        @Override public HealthCheckResult healthCheck() { return HealthCheckResult.healthy(); }
        @Override public boolean isReady() { return false; }
    }

    static class FailingModule implements AiDrivenModule {
        private final String id;
        FailingModule(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public ModuleType type() { return ModuleType.EXTENSION; }
        @Override public void initialize(ModuleContext context) throws ModuleInitializationException {
            throw new ModuleInitializationException(id, "Simulated failure");
        }
        @Override public HealthCheckResult healthCheck() { return HealthCheckResult.unhealthy("Not initialized"); }
        @Override public boolean isReady() { return false; }
    }
}
