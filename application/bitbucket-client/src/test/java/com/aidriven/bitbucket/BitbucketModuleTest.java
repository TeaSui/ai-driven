package com.aidriven.bitbucket;

import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.ServiceModule;
import com.aidriven.spi.SourceControlModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class BitbucketModuleTest {

    private BitbucketModule module;

    @BeforeEach
    void setUp() {
        module = new BitbucketModule();
    }

    @Test
    void should_have_correct_module_id() {
        assertEquals("bitbucket", module.moduleId());
    }

    @Test
    void should_implement_source_control_module() {
        assertTrue(module instanceof SourceControlModule);
    }

    @Test
    void should_not_be_healthy_before_init() {
        assertFalse(module.isHealthy());
    }

    @Test
    void should_initialize_with_valid_config() throws Exception {
        module.initialize(Map.of(
                "workspace", "test-ws",
                "repoSlug", "test-repo",
                "username", "user",
                "appPassword", "pass"));

        assertTrue(module.isHealthy());
        assertNotNull(module.getClient());
    }

    @Test
    void should_fail_without_workspace() {
        assertThrows(ModuleInitializationException.class,
                () -> module.initialize(Map.of(
                        "repoSlug", "repo",
                        "username", "user",
                        "appPassword", "pass")));
    }

    @Test
    void should_throw_when_getting_client_before_init() {
        assertThrows(IllegalStateException.class, module::getClient);
    }

    @Test
    void should_shutdown_cleanly() throws Exception {
        module.initialize(Map.of(
                "workspace", "ws",
                "repoSlug", "repo",
                "username", "user",
                "appPassword", "pass"));

        module.shutdown();

        assertFalse(module.isHealthy());
    }

    @Test
    void should_be_discoverable_via_service_loader() {
        ServiceLoader<ServiceModule> loader = ServiceLoader.load(ServiceModule.class);
        boolean found = false;
        for (ServiceModule m : loader) {
            if ("bitbucket".equals(m.moduleId())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "BitbucketModule should be discoverable via ServiceLoader");
    }
}
