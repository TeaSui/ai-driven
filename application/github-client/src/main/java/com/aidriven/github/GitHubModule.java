package com.aidriven.github;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.spi.ModuleDescriptor;
import com.aidriven.core.spi.ServiceProviderRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Module descriptor for the GitHub integration.
 *
 * <p>Registers a {@link GitHubClient} as a {@link SourceControlClient}
 * provider with qualifier "github".</p>
 *
 * <p>Required config keys:</p>
 * <ul>
 *   <li>{@code GITHUB_TOKEN} — GitHub personal access token</li>
 *   <li>{@code GITHUB_OWNER} — Repository owner/organization</li>
 *   <li>{@code GITHUB_REPO} — Repository name</li>
 * </ul>
 */
@Slf4j
public class GitHubModule implements ModuleDescriptor {

    @Override
    public String name() {
        return "github";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public List<Class<?>> providedServices() {
        return List.of(SourceControlClient.class);
    }

    @Override
    public List<String> requiredConfigKeys() {
        return List.of("GITHUB_TOKEN", "GITHUB_OWNER", "GITHUB_REPO");
    }

    @Override
    public void initialize(ServiceProviderRegistry registry, Map<String, String> config) {
        String token = config.get("GITHUB_TOKEN");
        String owner = config.get("GITHUB_OWNER");
        String repo = config.get("GITHUB_REPO");

        GitHubClient client = GitHubClient.fromRepoUrl(
                String.format("https://github.com/%s/%s", owner, repo), token);

        registry.register(SourceControlClient.class, "github", client);
        log.info("GitHub module initialized for {}/{}", owner, repo);
    }

    @Override
    public int priority() {
        return 50;
    }
}
