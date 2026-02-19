package com.aidriven.bitbucket;

import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.spi.ModuleDescriptor;
import com.aidriven.core.spi.ServiceProviderRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Module descriptor for the Bitbucket integration.
 *
 * <p>Registers a {@link BitbucketClient} as a {@link SourceControlClient}
 * provider with qualifier "bitbucket".</p>
 *
 * <p>Required config keys:</p>
 * <ul>
 *   <li>{@code BITBUCKET_USERNAME} — Bitbucket username</li>
 *   <li>{@code BITBUCKET_APP_PASSWORD} — Bitbucket app password</li>
 *   <li>{@code BITBUCKET_WORKSPACE} — Default workspace</li>
 *   <li>{@code BITBUCKET_REPO_SLUG} — Default repository slug</li>
 * </ul>
 */
@Slf4j
public class BitbucketModule implements ModuleDescriptor {

    private BitbucketClient client;

    @Override
    public String name() {
        return "bitbucket";
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
        return List.of(
                "BITBUCKET_USERNAME",
                "BITBUCKET_APP_PASSWORD",
                "BITBUCKET_WORKSPACE",
                "BITBUCKET_REPO_SLUG");
    }

    @Override
    public void initialize(ServiceProviderRegistry registry, Map<String, String> config) {
        String username = config.get("BITBUCKET_USERNAME");
        String appPassword = config.get("BITBUCKET_APP_PASSWORD");
        String workspace = config.get("BITBUCKET_WORKSPACE");
        String repoSlug = config.get("BITBUCKET_REPO_SLUG");

        client = BitbucketClient.fromRepoUrl(
                String.format("https://bitbucket.org/%s/%s", workspace, repoSlug),
                username, appPassword);

        registry.register(SourceControlClient.class, "bitbucket", client);
        log.info("Bitbucket module initialized for {}/{}", workspace, repoSlug);
    }

    @Override
    public int priority() {
        return 50;
    }
}
