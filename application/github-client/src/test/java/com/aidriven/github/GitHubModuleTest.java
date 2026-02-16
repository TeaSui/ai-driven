package com.aidriven.github;

import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.ServiceModule;
import com.aidriven.spi.SourceControlModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class GitHubModuleTest {

    private GitHubModule module;

    @BeforeEach
    void setUp() {
        module = new GitHubModule();
    }

    @Test
    void should_have_correct_module_id() {
        assertEquals("github", module.moduleId());
    }

    @Test
    void should_implement_source_control_module() {
        assertTrue(module instanceof SourceControlModule);
    }

    @Test
    void should_initialize_with_valid_config() throws Exception {
        module.initialize(Map.of(
                "owner", "test-org",
                "repo", "test-repo",
                "token", "ghp_test123"));

        assertTrue(module.isHealthy());
        assertNotNull(module.getClient());
    }

    @Test
    void should_fail_without_token() {
        assertThrows(ModuleInitializationException.class,
                () -> module.initialize(Map.of(
                        "owner", "org",
                        "repo", "repo")));
    }

    @Test
    void should_throw_when_getting_client_before_init() {
        assertThrows(IllegalStateException.class, module::getClient);
    }

    @Test
    void should_shutdown_cleanly() throws Exception {
        module.initialize(Map.of(
                "owner", "org",
                "repo", "repo",
                "token", "token"));

        module.shutdown();

        assertFalse(module.isHealthy());
    }

    @Test
    void should_be_discoverable_via_service_loader() {
        ServiceLoader<ServiceModule> loader = ServiceLoader.load(ServiceModule.class);
        boolean found = false;
        for (ServiceModule m : loader) {
            if ("github".equals(m.moduleId())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "GitHubModule should be discoverable via ServiceLoader");
    }
}
