package com.aidriven.bitbucket;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;

import java.util.Map;
import java.util.Set;

/**
 * Service descriptor for the Bitbucket source control module.
 * Discovered via {@link java.util.ServiceLoader} when the bitbucket-client
 * JAR is on the classpath.
 */
public class BitbucketServiceDescriptor implements ServiceDescriptor {

    @Override
    public String id() {
        return "bitbucket";
    }

    @Override
    public String displayName() {
        return "Bitbucket Cloud";
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
                "bitbucket.workspace",
                "bitbucket.repoSlug",
                "bitbucket.username",
                "bitbucket.appPassword");
    }

    @Override
    public Map<String, String> optionalConfigDefaults() {
        return Map.of(
                "bitbucket.apiBase", "https://api.bitbucket.org/2.0",
                "bitbucket.connectTimeoutSeconds", "30");
    }
}
