package com.aidriven.lambda.source;

import com.aidriven.core.source.Platform;
import com.aidriven.core.source.PlatformResolver;
import com.aidriven.core.source.RepositoryResolver;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.lambda.factory.ServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Centralises all platform + repository resolution logic so it is not
 * duplicated across {@code AgentProcessorHandler}, {@code PrCreatorHandler},
 * and any future handler.
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li>Explicit {@code platform}, {@code owner}, {@code slug} parameters
 *       (used when the caller already knows the target)</li>
 *   <li>Ticket labels — {@code repo:owner/slug} and {@code platform:github}</li>
 *   <li>Environment defaults ({@code DEFAULT_PLATFORM}, {@code DEFAULT_WORKSPACE},
 *       {@code DEFAULT_REPO})</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class SourceControlClientResolver {

    private final ServiceFactory serviceFactory;

    /**
     * Resolves a {@link SourceControlClient} from an explicit platform, owner, and slug.
     * Falls back to defaults when parameters are blank.
     */
    public SourceControlClient resolve(String platform, String owner, String slug) {
        String effectivePlatform = (platform != null && !platform.isBlank())
                ? platform.toUpperCase()
                : serviceFactory.getAppConfig().getDefaultPlatform();

        return switch (effectivePlatform) {
            case "GITHUB" -> {
                log.debug("Resolved GitHub client for {}/{}", owner, slug);
                yield serviceFactory.getGitHubClient(owner, slug);
            }
            default -> {
                log.debug("Resolved Bitbucket client for {}/{}", owner, slug);
                yield serviceFactory.getBitbucketClient(owner, slug);
            }
        };
    }

    /**
     * Resolves a {@link SourceControlClient} from Jira ticket labels and environment
     * defaults.
     *
     * @param labels Jira ticket labels (may be null or empty)
     */
    public SourceControlClient resolveFromLabels(List<String> labels) {
        Platform platform = PlatformResolver.resolve(
                labels, null, serviceFactory.getAppConfig().getDefaultPlatform());

        RepositoryResolver.ResolvedRepository repo = RepositoryResolver.resolve(
                labels, null,
                serviceFactory.getAppConfig().getDefaultWorkspace(),
                serviceFactory.getAppConfig().getDefaultRepo(),
                serviceFactory.getAppConfig().getDefaultPlatform());

        String owner = repo != null ? repo.owner() : null;
        String slug = repo != null ? repo.repo() : null;

        return switch (platform) {
            case GITHUB -> {
                log.debug("Resolved GitHub client from labels for {}/{}", owner, slug);
                yield serviceFactory.getGitHubClient(owner, slug);
            }
            case BITBUCKET -> {
                String effectiveOwner = owner != null ? owner
                        : serviceFactory.getAppConfig().getDefaultWorkspace();
                String effectiveSlug = slug != null ? slug
                        : serviceFactory.getAppConfig().getDefaultRepo();
                log.debug("Resolved Bitbucket client from labels for {}/{}", effectiveOwner, effectiveSlug);
                yield serviceFactory.getBitbucketClient(effectiveOwner, effectiveSlug);
            }
        };
    }
}
