package com.aidriven.bitbucket;

import com.aidriven.spi.ModuleInitializationException;
import com.aidriven.spi.SourceControlModule;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Bitbucket Cloud module implementing the {@link SourceControlModule} SPI.
 *
 * <p>Required configuration keys:
 * <ul>
 *   <li>{@code workspace} — Bitbucket workspace</li>
 *   <li>{@code repoSlug} — Repository slug</li>
 *   <li>{@code username} — Bitbucket username</li>
 *   <li>{@code appPassword} — Bitbucket app password</li>
 * </ul>
 * </p>
 */
@Slf4j
public class BitbucketModule implements SourceControlModule {

    private BitbucketClient client;
    private volatile boolean healthy = false;

    @Override
    public String moduleId() {
        return "bitbucket";
    }

    @Override
    public String displayName() {
        return "Bitbucket Cloud";
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public void initialize(Map<String, String> config) throws ModuleInitializationException {
        try {
            String workspace = requireConfig(config, "workspace");
            String repoSlug = requireConfig(config, "repoSlug");
            String username = requireConfig(config, "username");
            String appPassword = requireConfig(config, "appPassword");

            String auth = username + ":" + appPassword;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            String authHeader = "Basic " + encodedAuth;

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            this.client = new BitbucketClient(
                    authHeader, httpClient,
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    workspace, repoSlug);
            this.healthy = true;

            log.info("Bitbucket module initialized: {}/{}", workspace, repoSlug);
        } catch (ModuleInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModuleInitializationException("bitbucket", e.getMessage(), e);
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
        log.info("Bitbucket module shut down");
    }

    public BitbucketClient getClient() {
        if (client == null) {
            throw new IllegalStateException("Bitbucket module is not initialized");
        }
        return client;
    }

    private String requireConfig(Map<String, String> config, String key) throws ModuleInitializationException {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new ModuleInitializationException("bitbucket", "Missing required config: " + key);
        }
        return value;
    }
}
