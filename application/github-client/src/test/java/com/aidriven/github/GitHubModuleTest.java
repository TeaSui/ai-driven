package com.aidriven.github;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.spi.ServiceProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitHubModuleTest {

    @Test
    void should_have_correct_metadata() {
        GitHubModule module = new GitHubModule();

        assertEquals("github", module.name());
        assertEquals("1.0.0", module.version());
        assertEquals(List.of(SourceControlClient.class), module.providedServices());
        assertEquals(50, module.priority());
    }

    @Test
    void should_require_config_keys() {
        GitHubModule module = new GitHubModule();

        List<String> required = module.requiredConfigKeys();
        assertTrue(required.contains("GITHUB_TOKEN"));
        assertTrue(required.contains("GITHUB_OWNER"));
        assertTrue(required.contains("GITHUB_REPO"));
    }

    @Test
    void should_register_source_control_client() {
        GitHubModule module = new GitHubModule();
        ServiceProviderRegistry registry = new ServiceProviderRegistry();

        module.initialize(registry, Map.of(
                "GITHUB_TOKEN", "ghp_test",
                "GITHUB_OWNER", "owner",
                "GITHUB_REPO", "repo"));

        assertTrue(registry.isRegistered(SourceControlClient.class, "github"));
        assertNotNull(registry.get(SourceControlClient.class, "github"));
    }
}
