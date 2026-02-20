package com.aidriven.jira;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;

import java.util.Map;
import java.util.Set;

/**
 * Service descriptor for the Jira issue tracker module.
 * Discovered via {@link java.util.ServiceLoader} when the jira-client
 * JAR is on the classpath.
 */
public class JiraServiceDescriptor implements ServiceDescriptor {

    @Override
    public String id() {
        return "jira";
    }

    @Override
    public String displayName() {
        return "Jira Cloud";
    }

    @Override
    public ServiceCategory category() {
        return ServiceCategory.ISSUE_TRACKER;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of(
                "jira.baseUrl",
                "jira.email",
                "jira.apiToken");
    }

    @Override
    public Map<String, String> optionalConfigDefaults() {
        return Map.of(
                "jira.connectTimeoutSeconds", "30");
    }
}
