package com.aidriven.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    void should_register_module_provider() {
        registry.register(stubProvider("jira-client", ModuleCategory.ISSUE_TRACKER));

        assertEquals(1, registry.size());
        assertTrue(registry.getProvider("jira-client").isPresent());
    }

    @Test
    void should_throw_on_duplicate_registration() {
        registry.register(stubProvider("jira-client", ModuleCategory.ISSUE_TRACKER));

        assertThrows(IllegalArgumentException.class, () ->
                registry.register(stubProvider("jira-client", ModuleCategory.ISSUE_TRACKER)));
    }

    @Test
    void should_throw_on_null_provider() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    // --- Listing ---

    @Test
    void should_list_all_modules_sorted() {
        registry.register(stubProvider("github-client", ModuleCategory.SOURCE_CONTROL));
        registry.register(stubProvider("bitbucket-client", ModuleCategory.SOURCE_CONTROL));
        registry.register(stubProvider("jira-client", ModuleCategory.ISSUE_TRACKER));

        List<ModuleDescriptor> all = registry.listModules();

        assertEquals(3, all.size());
        assertEquals("bitbucket-client", all.get(0).id());
        assertEquals("github-client", all.get(1).id());
        assertEquals("jira-client", all.get(2).id());
    }

    @Test
    void should_filter_by_category() {
        registry.register(stubProvider("github-client", ModuleCategory.SOURCE_CONTROL));
        registry.register(stubProvider("jira-client", ModuleCategory.ISSUE_TRACKER));

        List<ModuleDescriptor> scModules = registry.listModules(ModuleCategory.SOURCE_CONTROL);

        assertEquals(1, scModules.size());
        assertEquals("github-client", scModules.get(0).id());
    }

    // --- Dependency Validation ---

    @Test
    void should_validate_satisfied_dependencies() {
        registry.register(stubProvider("core", ModuleCategory.ORCHESTRATION));
        registry.register(stubProviderWithDeps("jira-client", ModuleCategory.ISSUE_TRACKER, "core"));

        List<String> errors = registry.validateDependencies(Set.of("core", "jira-client"));

        assertTrue(errors.isEmpty());
    }

    @Test
    void should_detect_missing_dependency() {
        registry.register(stubProviderWithDeps("jira-client", ModuleCategory.ISSUE_TRACKER, "core"));

        List<String> errors = registry.validateDependencies(Set.of("jira-client"));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("requires 'core'"));
    }

    @Test
    void should_detect_unregistered_module() {
        List<String> errors = registry.validateDependencies(Set.of("nonexistent"));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("not registered"));
    }

    // --- Configuration Validation ---

    @Test
    void should_validate_configuration_present() {
        registry.register(stubProviderWithConfigs("jira-client", "jira.baseUrl", "jira.secretArn"));

        TenantContext context = TenantContext.builder("tenant-1")
                .enableModule("jira-client")
                .config("jira.baseUrl", "https://test.atlassian.net")
                .config("jira.secretArn", "arn:aws:secret")
                .build();

        List<String> missing = registry.validateConfiguration(context);
        assertTrue(missing.isEmpty());
    }

    @Test
    void should_detect_missing_configuration() {
        registry.register(stubProviderWithConfigs("jira-client", "jira.baseUrl", "jira.secretArn"));

        TenantContext context = TenantContext.builder("tenant-1")
                .enableModule("jira-client")
                .config("jira.baseUrl", "https://test.atlassian.net")
                .build();

        List<String> missing = registry.validateConfiguration(context);
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).contains("jira.secretArn"));
    }

    // --- Initialization Order ---

    @Test
    void should_resolve_initialization_order() {
        registry.register(stubProvider("core", ModuleCategory.ORCHESTRATION));
        registry.register(stubProviderWithDeps("jira-client", ModuleCategory.ISSUE_TRACKER, "core"));
        registry.register(stubProviderWithDeps("tool-tracker", ModuleCategory.ISSUE_TRACKER, "jira-client", "core"));

        List<String> order = registry.resolveInitializationOrder(
                Set.of("core", "jira-client", "tool-tracker"));

        // core must come before jira-client, jira-client before tool-tracker
        assertTrue(order.indexOf("core") < order.indexOf("jira-client"));
        assertTrue(order.indexOf("jira-client") < order.indexOf("tool-tracker"));
    }

    @Test
    void should_detect_circular_dependency() {
        registry.register(stubProviderWithDeps("a", ModuleCategory.DATA, "b"));
        registry.register(stubProviderWithDeps("b", ModuleCategory.DATA, "a"));

        assertThrows(IllegalStateException.class, () ->
                registry.resolveInitializationOrder(Set.of("a", "b")));
    }

    // --- Initialization ---

    @Test
    void should_initialize_modules_for_tenant() throws Exception {
        StubModuleProvider core = stubProvider("core", ModuleCategory.ORCHESTRATION);
        StubModuleProvider jira = stubProviderWithDeps("jira-client", ModuleCategory.ISSUE_TRACKER, "core");

        registry.register(core);
        registry.register(jira);

        TenantContext context = TenantContext.builder("tenant-1")
                .enableModule("core")
                .enableModule("jira-client")
                .build();

        List<String> result = registry.initializeForTenant(context);

        assertEquals(2, result.size());
        assertTrue(core.wasInitialized);
        assertTrue(jira.wasInitialized);
    }

    @Test
    void should_throw_when_module_not_found_during_init() {
        TenantContext context = TenantContext.builder("tenant-1")
                .enableModule("nonexistent")
                .build();

        assertThrows(ModuleInitializationException.class, () ->
                registry.initializeForTenant(context));
    }

    // --- Health Check ---

    @Test
    void should_return_health_for_initialized_modules() throws Exception {
        registry.register(stubProvider("core", ModuleCategory.ORCHESTRATION));

        TenantContext context = TenantContext.builder("t1")
                .enableModule("core")
                .build();
        registry.initializeForTenant(context);

        Map<String, HealthStatus> health = registry.healthCheck();

        assertEquals(1, health.size());
        assertTrue(health.get("core").isHealthy());
    }

    // --- Shutdown ---

    @Test
    void should_shutdown_all_initialized_modules() throws Exception {
        StubModuleProvider core = stubProvider("core", ModuleCategory.ORCHESTRATION);
        registry.register(core);

        TenantContext context = TenantContext.builder("t1")
                .enableModule("core")
                .build();
        registry.initializeForTenant(context);

        registry.shutdownAll();

        assertTrue(core.wasShutdown);
    }

    // --- Helpers ---

    private StubModuleProvider stubProvider(String id, ModuleCategory category) {
        return new StubModuleProvider(
                ModuleDescriptor.builder(id, category).build());
    }

    private StubModuleProvider stubProviderWithDeps(String id, ModuleCategory category, String... deps) {
        return new StubModuleProvider(
                ModuleDescriptor.builder(id, category).dependencies(deps).build());
    }

    private StubModuleProvider stubProviderWithConfigs(String id, String... configs) {
        return new StubModuleProvider(
                ModuleDescriptor.builder(id, ModuleCategory.ISSUE_TRACKER)
                        .requiredConfigs(configs).build());
    }

    static class StubModuleProvider implements ModuleProvider {
        private final ModuleDescriptor descriptor;
        boolean wasInitialized = false;
        boolean wasShutdown = false;

        StubModuleProvider(ModuleDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public ModuleDescriptor descriptor() { return descriptor; }

        @Override
        public void initialize(TenantContext context) {
            wasInitialized = true;
        }

        @Override
        public HealthStatus healthCheck() {
            return HealthStatus.healthy("OK");
        }

        @Override
        public void shutdown() {
            wasShutdown = true;
        }

        @Override
        public boolean isInitialized() { return wasInitialized; }
    }
}
