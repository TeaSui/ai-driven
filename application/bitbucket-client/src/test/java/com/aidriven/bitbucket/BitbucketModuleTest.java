package com.aidriven.bitbucket;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.spi.ServiceProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BitbucketModuleTest {

    @Test
    void should_have_correct_metadata() {
        BitbucketModule module = new BitbucketModule();

        assertEquals("bitbucket", module.name());
        assertEquals("1.0.0", module.version());
        assertEquals(List.of(SourceControlClient.class), module.providedServices());
        assertEquals(50, module.priority());
    }

    @Test
    void should_require_config_keys() {
        BitbucketModule module = new BitbucketModule();

        List<String> required = module.requiredConfigKeys();
        assertTrue(required.contains("BITBUCKET_USERNAME"));
        assertTrue(required.contains("BITBUCKET_APP_PASSWORD"));
        assertTrue(required.contains("BITBUCKET_WORKSPACE"));
        assertTrue(required.contains("BITBUCKET_REPO_SLUG"));
    }

    @Test
    void should_register_source_control_client() {
        BitbucketModule module = new BitbucketModule();
        ServiceProviderRegistry registry = new ServiceProviderRegistry();

        module.initialize(registry, Map.of(
                "BITBUCKET_USERNAME", "user",
                "BITBUCKET_APP_PASSWORD", "pass",
                "BITBUCKET_WORKSPACE", "ws",
                "BITBUCKET_REPO_SLUG", "repo"));

        assertTrue(registry.isRegistered(SourceControlClient.class, "bitbucket"));
        assertNotNull(registry.get(SourceControlClient.class, "bitbucket"));
    }
}
