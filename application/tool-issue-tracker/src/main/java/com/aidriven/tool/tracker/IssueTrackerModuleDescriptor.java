package com.aidriven.tool.tracker;

import java.util.List;
import java.util.Map;

/**
 * Provides module descriptors for issue tracker integrations.
 * Each supported tracker (Jira, and future Linear/Notion) is a separate module.
 */
public final class IssueTrackerModuleDescriptor {

    private IssueTrackerModuleDescriptor() {
    }

    public static final String JIRA_MODULE_ID = "issue_tracker_jira";

    /**
     * Required config keys for Jira integration.
     */
    public static final List<String> JIRA_REQUIRED_KEYS = List.of(
            "jira_secret_arn",
            "jira_base_url");

    /**
     * Default configuration for Jira.
     */
    public static final Map<String, String> JIRA_DEFAULTS = Map.of(
            "api_version", "3");
}
