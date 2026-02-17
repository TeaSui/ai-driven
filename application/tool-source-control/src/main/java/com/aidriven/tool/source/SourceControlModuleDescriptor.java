package com.aidriven.tool.source;

import java.util.List;
import java.util.Map;

/**
 * Provides module descriptors for source control integrations.
 * Each supported platform (Bitbucket, GitHub) is a separate module
 * that can be independently enabled per tenant.
 */
public final class SourceControlModuleDescriptor {

    private SourceControlModuleDescriptor() {
    }

    public static final String BITBUCKET_MODULE_ID = "source_control_bitbucket";
    public static final String GITHUB_MODULE_ID = "source_control_github";

    /**
     * Required config keys for Bitbucket integration.
     */
    public static final List<String> BITBUCKET_REQUIRED_KEYS = List.of(
            "bitbucket_secret_arn",
            "bitbucket_workspace",
            "bitbucket_repo_slug");

    /**
     * Required config keys for GitHub integration.
     */
    public static final List<String> GITHUB_REQUIRED_KEYS = List.of(
            "github_secret_arn",
            "github_owner",
            "github_repo");

    /**
     * Default configuration for Bitbucket.
     */
    public static final Map<String, String> BITBUCKET_DEFAULTS = Map.of(
            "api_version", "2.0");

    /**
     * Default configuration for GitHub.
     */
    public static final Map<String, String> GITHUB_DEFAULTS = Map.of(
            "api_version", "v3");
}
