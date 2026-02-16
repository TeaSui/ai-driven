package com.aidriven.github;

import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.SourceControlModule;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * GitHub module implementing the {@link SourceControlModule} SPI.
 *
 * <p>Required configuration keys:
 * <ul>
 *   <li>{@code owner} — GitHub repository owner</li>
 *   <li>{@code repo} — GitHub repository name</li>
 *   <li>{@code token} — GitHub personal access token</li>
 * </ul>
 * </p>
 */
@Slf4j
public class GitHubModule implements SourceControlModule {

    private GitHubClient client;
    private volatile boolean healthy = false;

    @Override
    public String moduleId() {
        return "github";
    }

    @Override
    public String displayName() {
        return "GitHub";
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public void initialize(Map<String, String> config) throws ModuleInitializationException {
        try {
            String owner = requireConfig(config, "owner");
            String repo = requireConfig(config, "repo");
            String token = requireConfig(config, "token");

            String authHeader = "Bearer " + token;
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            this.client = new GitHubClient(
                    authHeader, httpClient,
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    owner, repo);
            this.healthy = true;

            log.info("GitHub module initialized: {}/{}", owner, repo);
        } catch (ModuleInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModuleInitializationException("github", e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public void shutdown() {
        this.healthy = false;
        this.client = null;
        log.info("GitHub module shut down");
    }

    public GitHubClient getClient() {
        if (client == null) {
            throw new IllegalStateException("GitHub module is not initialized");
        }
        return client;
    }

    private String requireConfig(Map<String, String> config, String key) throws ModuleInitializationException {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new ModuleInitializationException("github", "Missing required config: " + key);
        }
        return value;
    }
}
