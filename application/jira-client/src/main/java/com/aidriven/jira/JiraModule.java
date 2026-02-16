package com.aidriven.jira;

import com.aidriven.spi.IssueTrackerModule;
import com.aidriven.spi.ModuleInitializationException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Jira Cloud module implementing the {@link IssueTrackerModule} SPI.
 *
 * <p>This module wraps {@link JiraClient} and exposes it as a pluggable
 * service module that can be discovered via {@code ServiceLoader} and
 * configured per tenant.</p>
 *
 * <p>Required configuration keys:
 * <ul>
 *   <li>{@code baseUrl} — Jira Cloud base URL (e.g., https://acme.atlassian.net)</li>
 *   <li>{@code email} — Jira user email</li>
 *   <li>{@code apiToken} — Jira API token</li>
 * </ul>
 * </p>
 */
@Slf4j
public class JiraModule implements IssueTrackerModule {

    private JiraClient client;
    private volatile boolean healthy = false;

    @Override
    public String moduleId() {
        return "jira";
    }

    @Override
    public String displayName() {
        return "Jira Cloud";
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public void initialize(Map<String, String> config) throws ModuleInitializationException {
        try {
            String baseUrl = requireConfig(config, "baseUrl");
            String email = requireConfig(config, "email");
            String apiToken = requireConfig(config, "apiToken");

            this.client = new JiraClient(baseUrl, email, apiToken);
            this.healthy = true;

            log.info("Jira module initialized: {}", baseUrl);
        } catch (ModuleInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModuleInitializationException("jira", e.getMessage(), e);
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
        log.info("Jira module shut down");
    }

    /**
     * Returns the underlying JiraClient.
     *
     * @return JiraClient instance
     * @throws IllegalStateException if the module is not initialized
     */
    public JiraClient getClient() {
        if (client == null) {
            throw new IllegalStateException("Jira module is not initialized");
        }
        return client;
    }

    private String requireConfig(Map<String, String> config, String key) throws ModuleInitializationException {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new ModuleInitializationException("jira", "Missing required config: " + key);
        }
        return value;
    }
}
