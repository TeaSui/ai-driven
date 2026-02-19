package com.aidriven.jira;

import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.core.spi.ModuleDescriptor;
import com.aidriven.core.spi.ServiceProviderRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Module descriptor for the Jira integration.
 *
 * <p>Registers a {@link JiraClient} as an {@link IssueTrackerClient}
 * provider with qualifier "jira".</p>
 *
 * <p>Required config keys:</p>
 * <ul>
 *   <li>{@code JIRA_BASE_URL} — Jira Cloud base URL</li>
 *   <li>{@code JIRA_EMAIL} — Jira user email</li>
 *   <li>{@code JIRA_API_TOKEN} — Jira API token</li>
 * </ul>
 */
@Slf4j
public class JiraModule implements ModuleDescriptor {

    @Override
    public String name() {
        return "jira";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public List<Class<?>> providedServices() {
        return List.of(IssueTrackerClient.class);
    }

    @Override
    public List<String> requiredConfigKeys() {
        return List.of("JIRA_BASE_URL", "JIRA_EMAIL", "JIRA_API_TOKEN");
    }

    @Override
    public void initialize(ServiceProviderRegistry registry, Map<String, String> config) {
        String baseUrl = config.get("JIRA_BASE_URL");
        String email = config.get("JIRA_EMAIL");
        String apiToken = config.get("JIRA_API_TOKEN");

        JiraClient client = new JiraClient(baseUrl, email, apiToken);

        registry.registerDefault(IssueTrackerClient.class, "jira", client);
        log.info("Jira module initialized for {}", baseUrl);
    }

    @Override
    public int priority() {
        return 50;
    }
}
