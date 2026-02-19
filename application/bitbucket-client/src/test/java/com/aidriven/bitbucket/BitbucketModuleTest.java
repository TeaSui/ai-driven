package com.aidriven.bitbucket;

import com.aidriven.spi.ModuleCategory;
import com.aidriven.spi.ModuleContext;
import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.SimpleModuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitbucketModuleTest {

    private BitbucketModule module;

    @BeforeEach
    void setUp() {
        module = new BitbucketModule();
    }

    @Test
    void should_have_correct_metadata() {
        assertEquals("bitbucket", module.id());
        assertEquals("Bitbucket Cloud", module.displayName());
        assertEquals(ModuleCategory.SOURCE_CONTROL, module.category());
    }

    @Test
    void should_not_be_healthy_before_initialization() {
        assertFalse(module.isHealthy());
    }

    @Test
    void should_initialize_successfully() throws Exception {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .secret("workspace", "test-ws")
                .secret("repoSlug", "test-repo")
                .secret("username", "user")
                .secret("appPassword", "pass")
                .build();

        module.initialize(ctx);

        assertTrue(module.isHealthy());
        assertNotNull(module.getClient());
    }

    @Test
    void should_throw_when_secrets_missing() {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1").build();

        assertThrows(ModuleInitializationException.class, () -> module.initialize(ctx));
    }

    @Test
    void should_throw_when_getting_client_before_init() {
        assertThrows(IllegalStateException.class, () -> module.getClient());
    }

    @Test
    void should_throw_when_getting_scoped_client_before_init() {
        assertThrows(IllegalStateException.class, () -> module.getClient("ws", "repo"));
    }

    @Test
    void should_return_scoped_client() throws Exception {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .secret("workspace", "test-ws")
                .secret("repoSlug", "test-repo")
                .secret("username", "user")
                .secret("appPassword", "pass")
                .build();

        module.initialize(ctx);

        assertNotNull(module.getClient("other-ws", "other-repo"));
    }

    @Test
    void should_shutdown_cleanly() throws Exception {
        ModuleContext ctx = SimpleModuleContext.builder("tenant-1")
                .secret("workspace", "test-ws")
                .secret("repoSlug", "test-repo")
                .secret("username", "user")
                .secret("appPassword", "pass")
                .build();

        module.initialize(ctx);
        assertTrue(module.isHealthy());

        module.shutdown();
        assertFalse(module.isHealthy());
    }
}
