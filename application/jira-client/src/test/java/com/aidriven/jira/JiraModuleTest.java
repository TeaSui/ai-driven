package com.aidriven.jira;

import com.aidriven.spi.IssueTrackerModule;
import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.ServiceModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class JiraModuleTest {

    private JiraModule module;

    @BeforeEach
    void setUp() {
        module = new JiraModule();
    }

    @Test
    void should_have_correct_module_id() {
        assertEquals("jira", module.moduleId());
    }

    @Test
    void should_have_display_name() {
        assertEquals("Jira Cloud", module.displayName());
    }

    @Test
    void should_have_no_dependencies() {
        assertTrue(module.dependencies().isEmpty());
    }

    @Test
    void should_implement_issue_tracker_module() {
        assertTrue(module instanceof IssueTrackerModule);
    }

    @Test
    void should_not_be_healthy_before_initialization() {
        assertFalse(module.isHealthy());
    }

    @Test
    void should_initialize_with_valid_config() throws Exception {
        module.initialize(Map.of(
                "baseUrl", "https://test.atlassian.net",
                "email", "test@test.com",
                "apiToken", "test-token"));

        assertTrue(module.isHealthy());
        assertNotNull(module.getClient());
    }

    @Test
    void should_fail_initialization_without_base_url() {
        assertThrows(ModuleInitializationException.class,
                () -> module.initialize(Map.of(
                        "email", "test@test.com",
                        "apiToken", "token")));
    }

    @Test
    void should_fail_initialization_without_email() {
        assertThrows(ModuleInitializationException.class,
                () -> module.initialize(Map.of(
                        "baseUrl", "https://test.atlassian.net",
                        "apiToken", "token")));
    }

    @Test
    void should_fail_initialization_without_api_token() {
        assertThrows(ModuleInitializationException.class,
                () -> module.initialize(Map.of(
                        "baseUrl", "https://test.atlassian.net",
                        "email", "test@test.com")));
    }

    @Test
    void should_throw_when_getting_client_before_init() {
        assertThrows(IllegalStateException.class, module::getClient);
    }

    @Test
    void should_shutdown_cleanly() throws Exception {
        module.initialize(Map.of(
                "baseUrl", "https://test.atlassian.net",
                "email", "test@test.com",
                "apiToken", "token"));

        module.shutdown();

        assertFalse(module.isHealthy());
        assertThrows(IllegalStateException.class, module::getClient);
    }

    @Test
    void should_be_discoverable_via_service_loader() {
        ServiceLoader<ServiceModule> loader = ServiceLoader.load(ServiceModule.class);
        boolean found = false;
        for (ServiceModule m : loader) {
            if ("jira".equals(m.moduleId())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "JiraModule should be discoverable via ServiceLoader");
    }
}
