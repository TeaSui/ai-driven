package com.aidriven.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRegistryTest {

    private ModuleRegistry registry;
    private ModuleContext context;

    @BeforeEach
    void setUp() {
        registry = new ModuleRegistry();
        context = SimpleModuleContext.builder("tenant-1")
                .config("baseUrl", "https://test.atlassian.net")
                .secret("apiToken", "test-token")
                .build();
    }

    @Test
    void should_register_and_retrieve_module() throws Exception {
        StubIssueTrackerModule module = new StubIssueTrackerModule();
        registry.register(module);
        registry.initialize("stub-tracker", context);

        IssueTrackerModule retrieved = registry.getModule("stub-tracker", IssueTrackerModule.class);
        assertNotNull(retrieved);
        assertEquals("stub-tracker", retrieved.id());
        assertTrue(retrieved.isHealthy());
    }

    @Test
    void should_throw_when_module_not_registered() {
        assertThrows(IllegalStateException.class,
                () -> registry.getModule("nonexistent", ServiceModule.class));
    }

    @Test
    void should_throw_when_module_not_initialized() {
        registry.register(new StubIssueTrackerModule());

        assertThrows(IllegalStateException.class,
                () -> registry.getModule("stub-tracker", IssueTrackerModule.class));
    }

    @Test
    void should_throw_when_module_type_mismatch() throws Exception {
        registry.register(new StubIssueTrackerModule());
        registry.initialize("stub-tracker", context);

        assertThrows(IllegalStateException.class,
                () -> registry.getModule("stub-tracker", SourceControlModule.class));
    }

    @Test
    void should_find_module_optionally() throws Exception {
        registry.register(new StubIssueTrackerModule());
        registry.initialize("stub-tracker", context);

        Optional<IssueTrackerModule> found = registry.findModule("stub-tracker", IssueTrackerModule.class);
        assertTrue(found.isPresent());

        Optional<IssueTrackerModule> notFound = registry.findModule("missing", IssueTrackerModule.class);
        assertTrue(notFound.isEmpty());
    }

    @Test
    void should_initialize_all_modules() {
        registry.register(new StubIssueTrackerModule());
        registry.register(new StubSourceControlModule());

        List<String> failures = registry.initializeAll(context);

        assertTrue(failures.isEmpty());
        assertEquals(2, registry.getInitializedModuleIds().size());
    }

    @Test
    void should_report_failed_initializations() {
        registry.register(new FailingModule());

        List<String> failures = registry.initializeAll(context);

        assertEquals(1, failures.size());
        assertEquals("failing-module", failures.get(0));
    }

    @Test
    void should_get_modules_by_category() throws Exception {
        registry.register(new StubIssueTrackerModule());
        registry.register(new StubSourceControlModule());
        registry.initializeAll(context);

        List<ServiceModule> trackers = registry.getModulesByCategory(ModuleCategory.ISSUE_TRACKER);
        assertEquals(1, trackers.size());

        List<ServiceModule> scm = registry.getModulesByCategory(ModuleCategory.SOURCE_CONTROL);
        assertEquals(1, scm.size());

        List<ServiceModule> monitoring = registry.getModulesByCategory(ModuleCategory.MONITORING);
        assertTrue(monitoring.isEmpty());
    }

    @Test
    void should_perform_health_check() throws Exception {
        registry.register(new StubIssueTrackerModule());
        registry.initializeAll(context);

        Map<String, Boolean> health = registry.healthCheck();
        assertEquals(1, health.size());
        assertTrue(health.get("stub-tracker"));
    }

    @Test
    void should_shutdown_all_modules() throws Exception {
        StubIssueTrackerModule module = new StubIssueTrackerModule();
        registry.register(module);
        registry.initializeAll(context);
        assertTrue(module.isHealthy());

        registry.shutdownAll();
        assertFalse(module.isHealthy());
        assertTrue(registry.getInitializedModuleIds().isEmpty());
    }

    @Test
    void should_return_registered_module_ids() {
        registry.register(new StubIssueTrackerModule());
        registry.register(new StubSourceControlModule());

        Set<String> ids = registry.getRegisteredModuleIds();
        assertEquals(Set.of("stub-tracker", "stub-scm"), ids);
    }

    @Test
    void should_replace_module_with_same_id() throws Exception {
        StubIssueTrackerModule first = new StubIssueTrackerModule();
        StubIssueTrackerModule second = new StubIssueTrackerModule();

        registry.register(first);
        registry.register(second);

        assertEquals(1, registry.size());
    }

    // --- Stub Modules ---

    static class StubIssueTrackerModule implements IssueTrackerModule {
        private boolean initialized = false;

        @Override
        public String id() { return "stub-tracker"; }

        @Override
        public String displayName() { return "Stub Issue Tracker"; }

        @Override
        public void initialize(ModuleContext context) {
            initialized = true;
        }

        @Override
        public boolean isHealthy() { return initialized; }

        @Override
        public void shutdown() { initialized = false; }

        @Override
        public com.aidriven.core.tracker.IssueTrackerClient getClient() {
            return null; // Stub
        }
    }

    static class StubSourceControlModule implements SourceControlModule {
        private boolean initialized = false;

        @Override
        public String id() { return "stub-scm"; }

        @Override
        public String displayName() { return "Stub SCM"; }

        @Override
        public void initialize(ModuleContext context) {
            initialized = true;
        }

        @Override
        public boolean isHealthy() { return initialized; }

        @Override
        public com.aidriven.core.source.SourceControlClient getClient() { return null; }

        @Override
        public com.aidriven.core.source.SourceControlClient getClient(String owner, String repo) { return null; }
    }

    static class FailingModule implements ServiceModule {
        @Override
        public String id() { return "failing-module"; }

        @Override
        public String displayName() { return "Failing Module"; }

        @Override
        public ModuleCategory category() { return ModuleCategory.MONITORING; }

        @Override
        public void initialize(ModuleContext context) throws ModuleInitializationException {
            throw new ModuleInitializationException("failing-module", "Intentional failure");
        }

        @Override
        public boolean isHealthy() { return false; }
    }
}
