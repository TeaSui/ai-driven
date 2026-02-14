package com.aidriven.core.source;

import java.util.List;

/**
 * Resolves the target repository dynamically from ticket labels,
 * custom fields, or default configuration.
 *
 * Resolution priority:
 * 1. Jira label: "repo:owner/repo-name"
 * 2. Repository URL parsing (from ticket custom field or config)
 * 3. Default owner/repo from environment
 */
public final class RepositoryResolver {

    private static final String LABEL_PREFIX = "repo:";

    private RepositoryResolver() {
    }

    /**
     * Resolved repository information.
     */
    public record ResolvedRepository(String owner, String repo, Platform platform) {
    }

    /**
     * Resolves repository from available context.
     *
     * @param labels          Jira ticket labels (may be null)
     * @param repoUrl         Repository URL from ticket/config (may be null)
     * @param defaultOwner    Default workspace/owner from env (may be null)
     * @param defaultRepo     Default repo slug from env (may be null)
     * @param defaultPlatform Default platform string from env (may be null)
     * @return Resolved repository, or null if insufficient information
     */
    public static ResolvedRepository resolve(List<String> labels, String repoUrl,
            String defaultOwner, String defaultRepo, String defaultPlatform) {

        // 1. Check labels for "repo:owner/repo-name"
        ResolvedRepository fromLabel = resolveFromLabels(labels, defaultPlatform);
        if (fromLabel != null) {
            return fromLabel;
        }

        // 2. Try to parse repository URL
        if (repoUrl != null && !repoUrl.isBlank()) {
            ResolvedRepository fromUrl = resolveFromUrl(repoUrl, defaultPlatform);
            if (fromUrl != null) {
                return fromUrl;
            }
        }

        // 3. Use defaults
        if (defaultOwner != null && !defaultOwner.isBlank()
                && defaultRepo != null && !defaultRepo.isBlank()) {
            Platform platform = PlatformResolver.resolve(labels, repoUrl, defaultPlatform);
            return new ResolvedRepository(defaultOwner, defaultRepo, platform);
        }

        return null;
    }

    /**
     * Extracts repository from Jira labels (e.g., "repo:owner/repo-name").
     */
    static ResolvedRepository resolveFromLabels(List<String> labels, String defaultPlatform) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        for (String label : labels) {
            String trimmed = label.trim();
            if (trimmed.toLowerCase().startsWith(LABEL_PREFIX)) {
                String repoPath = trimmed.substring(LABEL_PREFIX.length()).trim();
                String[] parts = repoPath.split("/");
                if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    Platform platform = PlatformResolver.resolve(labels, null, defaultPlatform);
                    return new ResolvedRepository(parts[0], parts[1], platform);
                }
            }
        }
        return null;
    }

    /**
     * Resolves repository from a URL by detecting the platform and parsing
     * owner/repo.
     */
    static ResolvedRepository resolveFromUrl(String repoUrl, String defaultPlatform) {
        Platform platform = Platform.fromUrl(repoUrl);
        if (platform == null) {
            return null;
        }

        try {
            String[] ownerRepo = parseOwnerRepoFromUrl(repoUrl);
            if (ownerRepo != null) {
                return new ResolvedRepository(ownerRepo[0], ownerRepo[1], platform);
            }
        } catch (Exception e) {
            // URL parsing failed, fall through
        }
        return null;
    }

    /**
     * Simple URL parser to extract owner/repo from common URL formats.
     */
    private static String[] parseOwnerRepoFromUrl(String url) {
        String trimmed = url.trim();
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        // SSH format: git@host:owner/repo
        int colonIdx = trimmed.indexOf(':');
        if (trimmed.startsWith("git@") && colonIdx > 0) {
            String path = trimmed.substring(colonIdx + 1);
            return splitOwnerRepo(path);
        }

        // HTTPS format: extract after host
        String[] hostPatterns = {
                "github.com/", "bitbucket.org/", "api.github.com/repos/",
                "api.bitbucket.org/2.0/repositories/"
        };

        for (String pattern : hostPatterns) {
            int idx = trimmed.indexOf(pattern);
            if (idx >= 0) {
                String path = trimmed.substring(idx + pattern.length());
                return splitOwnerRepo(path);
            }
        }

        return null;
    }

    private static String[] splitOwnerRepo(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return new String[] { parts[0], parts[1] };
        }
        return null;
    }
}
