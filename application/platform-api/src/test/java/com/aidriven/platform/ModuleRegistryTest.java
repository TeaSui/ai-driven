package com.aidriven.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRegistryTest {

    private ModuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModuleRegistry();
    }

    private ModuleDescriptor sourceControlModule(String id) {
        return new ModuleDescriptor(id, "Source Control: " + id,
                ModuleDescriptor.ModuleType.SOURCE_CONTROL,
                "Source control integration",
                List.of("secret_arn", "workspace"),
                Map.of());
    }

    private ModuleDescriptor issueTrackerModule(String id) {
        return new ModuleDescriptor(id, "Issue Tracker: " + id,
                ModuleDescriptor.ModuleType.ISSUE_TRACKER,
                "Issue tracker integration",
                List.of("secret_arn", "base_url"),
                Map.of());
    }

    @Test
    void should_register_and_retrieve_module() {
        ModuleDescriptor module = sourceControlModule("bitbucket");
        registry.register(module);

        Optional<ModuleDescriptor> result = registry.get("bitbucket");

        assertTrue(result.isPresent());
        assertEquals("bitbucket", result.get().moduleId());
        assertEquals(ModuleDescriptor.ModuleType.SOURCE_CONTROL, result.get().type());
    }

    @Test
    void should_return_empty_for_unknown_module() {
        assertTrue(registry.get("unknown").isEmpty());
    }

    @Test
    void should_throw_on_duplicate_registration() {
        registry.register(sourceControlModule("github"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(sourceControlModule("github")));
    }

    @Test
    void should_throw_on_null_descriptor() {
        assertThrows(NullPointerException.class,
                () -> registry.register(null));
    }

    @Test
    void should_list_all_modules() {
        registry.register(sourceControlModule("bitbucket"));
        registry.register(sourceControlModule("github"));
        registry.register(issueTrackerModule("jira"));

        assertEquals(3, registry.getAll().size());
        assertEquals(3, registry.size());
    }

    @Test
    void should_filter_by_type() {
        registry.register(sourceControlModule("bitbucket"));
        registry.register(sourceControlModule("github"));
        registry.register(issueTrackerModule("jira"));

        List<ModuleDescriptor> scModules = registry.getByType(
                ModuleDescriptor.ModuleType.SOURCE_CONTROL);

        assertEquals(2, scModules.size());
        assertTrue(scModules.stream().allMatch(
                m -> m.type() == ModuleDescriptor.ModuleType.SOURCE_CONTROL));
    }

    @Test
    void should_validate_tenant_config_success() {
        registry.register(sourceControlModule("bitbucket"));

        List<String> errors = registry.validateTenantConfig(
                List.of("bitbucket"),
                Map.of("secret_arn", "arn:aws:...", "workspace", "my-ws"));

        assertTrue(errors.isEmpty());
    }

    @Test
    void should_validate_tenant_config_missing_keys() {
        registry.register(sourceControlModule("bitbucket"));

        List<String> errors = registry.validateTenantConfig(
                List.of("bitbucket"),
                Map.of("secret_arn", "arn:aws:..."));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("workspace"));
    }

    @Test
    void should_validate_unknown_module() {
        List<String> errors = registry.validateTenantConfig(
                List.of("unknown-module"),
                Map.of());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Unknown module"));
    }

    @Test
    void should_validate_blank_config_value() {
        registry.register(sourceControlModule("bitbucket"));

        List<String> errors = registry.validateTenantConfig(
                List.of("bitbucket"),
                Map.of("secret_arn", "arn:aws:...", "workspace", "  "));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("workspace"));
    }

    @Test
    void should_return_registered_ids() {
        registry.register(sourceControlModule("bitbucket"));
        registry.register(issueTrackerModule("jira"));

        assertEquals(java.util.Set.of("bitbucket", "jira"), registry.getRegisteredIds());
    }
}
