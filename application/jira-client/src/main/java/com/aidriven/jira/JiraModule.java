package com.aidriven.jira;

import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.spi.IssueTrackerModule;
import com.aidriven.spi.ModuleContext;
import com.aidriven.spi.ModuleInitializationException;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Jira Cloud module implementing the {@link IssueTrackerModule} SPI.
 *
 * <p>Required configuration keys:</p>
 * <ul>
 *   <li>{@code baseUrl} — Jira Cloud base URL (e.g., https://company.atlassian.net)</li>
 * </ul>
 *
 * <p>Required secrets:</p>
 * <ul>
 *   <li>{@code email} — Jira user email</li>
 *   <li>{@code apiToken} — Jira API token</li>
 * </ul>
 */
@Slf4j
public class JiraModule implements IssueTrackerModule {

    private JiraClient client;
    private boolean initialized = false;

    @Override
    public String id() {
        return "jira";
    }

    @Override
    public String displayName() {
        return "Jira Cloud";
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of("baseUrl");
    }

    @Override
    public void initialize(ModuleContext context) throws ModuleInitializationException {
        try {
            String baseUrl = context.getConfig("baseUrl")
                    .orElseThrow(() -> new ModuleInitializationException(id(), "Missing config: baseUrl"));
            String email = context.getSecret("email");
            String apiToken = context.getSecret("apiToken");

            this.client = new JiraClient(baseUrl, email, apiToken);
            this.initialized = true;
            log.info("JiraModule initialized for tenant={} baseUrl={}", context.tenantId(), baseUrl);
        } catch (ModuleInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new ModuleInitializationException(id(), e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        return initialized && client != null;
    }

    @Override
    public void shutdown() {
        this.client = null;
        this.initialized = false;
    }

    @Override
    public IssueTrackerClient getClient() {
        if (!initialized) {
            throw new IllegalStateException("JiraModule is not initialized");
        }
        return client;
    }
}
