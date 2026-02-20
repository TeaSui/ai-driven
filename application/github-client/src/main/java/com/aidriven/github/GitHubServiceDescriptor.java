package com.aidriven.github;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;

import java.util.Map;
import java.util.Set;

/**
 * Service descriptor for the GitHub source control module.
 * Discovered via {@link java.util.ServiceLoader} when the github-client
 * JAR is on the classpath.
 */
public class GitHubServiceDescriptor implements ServiceDescriptor {

    @Override
    public String id() {
        return "github";
    }

    @Override
    public String displayName() {
        return "GitHub";
    }

    @Override
    public ServiceCategory category() {
        return ServiceCategory.SOURCE_CONTROL;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of(
                "github.owner",
                "github.repo",
                "github.token");
    }

    @Override
    public Map<String, String> optionalConfigDefaults() {
        return Map.of(
                "github.apiBase", "https://api.github.com",
                "github.connectTimeoutSeconds", "30");
    }
}
