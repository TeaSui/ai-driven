package com.aidriven.jira;

import com.aidriven.core.tracker.IssueTrackerClient;
import com.aidriven.core.spi.ServiceProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JiraModuleTest {

    @Test
    void should_have_correct_metadata() {
        JiraModule module = new JiraModule();

        assertEquals("jira", module.name());
        assertEquals("1.0.0", module.version());
        assertEquals(List.of(IssueTrackerClient.class), module.providedServices());
        assertEquals(50, module.priority());
    }

    @Test
    void should_require_config_keys() {
        JiraModule module = new JiraModule();

        List<String> required = module.requiredConfigKeys();
        assertTrue(required.contains("JIRA_BASE_URL"));
        assertTrue(required.contains("JIRA_EMAIL"));
        assertTrue(required.contains("JIRA_API_TOKEN"));
    }

    @Test
    void should_register_as_default_issue_tracker() {
        JiraModule module = new JiraModule();
        ServiceProviderRegistry registry = new ServiceProviderRegistry();

        module.initialize(registry, Map.of(
                "JIRA_BASE_URL", "https://test.atlassian.net",
                "JIRA_EMAIL", "test@test.com",
                "JIRA_API_TOKEN", "token"));

        assertTrue(registry.isRegistered(IssueTrackerClient.class, "jira"));
        assertNotNull(registry.getDefault(IssueTrackerClient.class));
    }
}
