package com.aidriven.spi;

/**
 * Categories of pluggable modules.
 * Each type corresponds to a core interface that the module must implement.
 */
public enum ModuleType {

    /** Source control platforms (Bitbucket, GitHub, GitLab, etc.) */
    SOURCE_CONTROL("source-control"),

    /** Issue/project trackers (Jira, Linear, Shortcut, etc.) */
    ISSUE_TRACKER("issue-tracker"),

    /** AI model providers (Claude, OpenAI, Bedrock, etc.) */
    AI_PROVIDER("ai-provider"),

    /** Notification/messaging (Slack, Teams, email, etc.) */
    MESSAGING("messaging"),

    /** Monitoring/observability (Datadog, CloudWatch, etc.) */
    MONITORING("monitoring"),

    /** Secret/credential providers (AWS SM, Vault, etc.) */
    SECRETS("secrets"),

    /** Storage providers (S3, GCS, etc.) */
    STORAGE("storage"),

    /** Custom/extension modules */
    EXTENSION("extension");

    private final String value;

    ModuleType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ModuleType fromValue(String value) {
        for (ModuleType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown module type: " + value);
    }
}
