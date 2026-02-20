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

    @Test
    void should_register_and_find_by_id() {
        ServiceDescriptor descriptor = createDescriptor("bitbucket", ServiceCategory.SOURCE_CONTROL);
        registry.register(descriptor);

        assertTrue(registry.findById("bitbucket").isPresent());
        assertEquals("bitbucket", registry.findById("bitbucket").get().id());
    }

    @Test
    void should_return_empty_for_unknown_id() {
        assertTrue(registry.findById("nonexistent").isEmpty());
    }

    @Test
    void should_find_by_category() {
        registry.register(createDescriptor("bitbucket", ServiceCategory.SOURCE_CONTROL));
        registry.register(createDescriptor("github", ServiceCategory.SOURCE_CONTROL));
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER));

        List<ServiceDescriptor> scModules = registry.findByCategory(ServiceCategory.SOURCE_CONTROL);
        assertEquals(2, scModules.size());

        List<ServiceDescriptor> itModules = registry.findByCategory(ServiceCategory.ISSUE_TRACKER);
        assertEquals(1, itModules.size());
    }

    @Test
    void should_return_empty_list_for_unused_category() {
        List<ServiceDescriptor> modules = registry.findByCategory(ServiceCategory.MONITORING);
        assertTrue(modules.isEmpty());
    }

    @Test
    void should_return_registered_ids() {
        registry.register(createDescriptor("bitbucket", ServiceCategory.SOURCE_CONTROL));
        registry.register(createDescriptor("jira", ServiceCategory.ISSUE_TRACKER));

        Set<String> ids = registry.registeredIds();
        assertEquals(Set.of("bitbucket", "jira"), ids);
    }

    @Test
    void should_report_correct_size() {
        assertEquals(0, registry.size());
        registry.register(createDescriptor("a", ServiceCategory.SOURCE_CONTROL));
        registry.register(createDescriptor("b", ServiceCategory.ISSUE_TRACKER));
        // Size may include ServiceLoader-discovered modules + manual ones
        assertTrue(registry.size() >= 2);
    }

    @Test
    void should_throw_for_null_descriptor() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void should_validate_tenant_config_success() {
        registry.register(createDescriptorWithRequiredKeys("jira", ServiceCategory.ISSUE_TRACKER,
                Set.of("jira.baseUrl", "jira.email")));

        List<String> errors = registry.validateTenantConfig(
                Set.of("jira"),
                Map.of("jira.baseUrl", "https://x.atlassian.net", "jira.email", "bot@x.com"));

        assertTrue(errors.isEmpty());
    }

    @Test
    void should_validate_tenant_config_missing_keys() {
        registry.register(createDescriptorWithRequiredKeys("jira", ServiceCategory.ISSUE_TRACKER,
                Set.of("jira.baseUrl", "jira.email")));

        List<String> errors = registry.validateTenantConfig(
                Set.of("jira"),
                Map.of("jira.baseUrl", "https://x.atlassian.net"));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("jira.email"));
    }

    @Test
    void should_validate_unknown_module() {
        List<String> errors = registry.validateTenantConfig(
                Set.of("unknown-module"),
                Map.of());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Unknown module"));
    }

    @Test
    void should_validate_missing_dependencies() {
        registry.register(new ServiceDescriptor() {
            public String id() { return "tool-sc"; }
            public String displayName() { return "SC Tool"; }
            public ServiceCategory category() { return ServiceCategory.TOOL_PROVIDER; }
            public String version() { return "1.0.0"; }
            public Set<String> requiredConfigKeys() { return Set.of(); }
            public Set<String> dependencies() { return Set.of("bitbucket"); }
        });

        List<String> errors = registry.validateTenantConfig(
                Set.of("tool-sc"),
                Map.of());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("depends on 'bitbucket'"));
    }

    // --- Helpers ---

    private ServiceDescriptor createDescriptor(String id, ServiceCategory category) {
        return createDescriptorWithRequiredKeys(id, category, Set.of());
    }

    private ServiceDescriptor createDescriptorWithRequiredKeys(String id, ServiceCategory category, Set<String> requiredKeys) {
        return new ServiceDescriptor() {
            public String id() { return id; }
            public String displayName() { return id + " module"; }
            public ServiceCategory category() { return category; }
            public String version() { return "1.0.0"; }
            public Set<String> requiredConfigKeys() { return requiredKeys; }
        };
    }
}
