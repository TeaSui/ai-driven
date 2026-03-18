package com.aidriven.core.source;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Centralises all platform + repository resolution logic so it is not
 * duplicated across handlers and controllers.
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li>Explicit {@code platform}, {@code owner}, {@code slug} parameters
 *       (used when the caller already knows the target)</li>
 *   <li>Ticket labels -- {@code repo:owner/slug} and {@code platform:github}</li>
 *   <li>Environment defaults ({@code DEFAULT_PLATFORM}, {@code DEFAULT_WORKSPACE},
 *       {@code DEFAULT_REPO})</li>
 * </ol>
 */
@Slf4j
public class SourceControlClientResolver {

    private final SourceControlClientFactory clientFactory;
    private final String defaultPlatform;
    private final String defaultWorkspace;
    private final String defaultRepo;

    /**
     * Functional interface for creating platform-specific source control clients.
     */
    @FunctionalInterface
    public interface SourceControlClientFactory {
        SourceControlClient create(String platform, String owner, String slug);
    }

    public SourceControlClientResolver(
            SourceControlClientFactory clientFactory,
            String defaultPlatform,
            String defaultWorkspace,
            String defaultRepo) {
        this.clientFactory = clientFactory;
        this.defaultPlatform = defaultPlatform;
        this.defaultWorkspace = defaultWorkspace;
        this.defaultRepo = defaultRepo;
    }

    /**
     * Resolves a {@link SourceControlClient} from an explicit platform, owner, and slug.
     * Falls back to defaults when parameters are blank.
     */
    public SourceControlClient resolve(String platform, String owner, String slug) {
        String effectivePlatform = (platform != null && !platform.isBlank())
                ? platform.toUpperCase()
                : defaultPlatform;

        return clientFactory.create(effectivePlatform, owner, slug);
    }

    /**
     * Resolves a {@link SourceControlClient} from Jira ticket labels and environment
     * defaults.
     *
     * @param labels Jira ticket labels (may be null or empty)
     */
    public SourceControlClient resolveFromLabels(List<String> labels) {
        Platform platform = PlatformResolver.resolve(
                labels, null, defaultPlatform);

        RepositoryResolver.ResolvedRepository repo = RepositoryResolver.resolve(
                labels, null, defaultWorkspace, defaultRepo, defaultPlatform);

        String owner = repo != null ? repo.owner() : null;
        String slug = repo != null ? repo.repo() : null;

        String effectiveOwner = owner != null ? owner : defaultWorkspace;
        String effectiveSlug = slug != null ? slug : defaultRepo;

        log.debug("Resolved {} client from labels for {}/{}", platform, effectiveOwner, effectiveSlug);
        return clientFactory.create(platform.name(), effectiveOwner, effectiveSlug);
    }
}
