package com.aidriven.core.source;

import java.util.List;

/**
 * Resolves which source control platform to use based on ticket labels,
 * repository URL, or default configuration.
 *
 * Resolution priority:
 * 1. Jira label: "platform:github" or "platform:bitbucket"
 * 2. Repository URL domain detection
 * 3. Default platform from environment
 */
public final class PlatformResolver {

    private static final String LABEL_PREFIX = "platform:";

    private PlatformResolver() {
    }

    /**
     * Resolves the platform from available context.
     *
     * @param labels          Jira ticket labels (may be null)
     * @param repoUrl         Repository URL (may be null)
     * @param defaultPlatform Fallback platform from config (may be null)
     * @return Resolved platform, or BITBUCKET as ultimate fallback
     */
    public static Platform resolve(List<String> labels, String repoUrl, String defaultPlatform) {
        // 1. Check labels
        Platform fromLabel = resolveFromLabels(labels);
        if (fromLabel != null) {
            return fromLabel;
        }

        // 2. Check repository URL
        Platform fromUrl = Platform.fromUrl(repoUrl);
        if (fromUrl != null) {
            return fromUrl;
        }

        // 3. Check default
        Platform fromDefault = Platform.fromString(defaultPlatform);
        if (fromDefault != null) {
            return fromDefault;
        }

        // 4. Ultimate fallback
        return Platform.BITBUCKET;
    }

    /**
     * Extracts platform from Jira labels (e.g., "platform:github").
     */
    static Platform resolveFromLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        for (String label : labels) {
            String lower = label.toLowerCase().trim();
            if (lower.startsWith(LABEL_PREFIX)) {
                String platformValue = lower.substring(LABEL_PREFIX.length());
                Platform platform = Platform.fromString(platformValue);
                if (platform != null) {
                    return platform;
                }
            }
        }
        return null;
    }
}
